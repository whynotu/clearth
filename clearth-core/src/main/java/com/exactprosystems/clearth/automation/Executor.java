/******************************************************************************
 * Copyright 2009-2019 Exactpro Systems Limited
 * https://www.exactpro.com
 * Build Software to Test Software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.exactprosystems.clearth.automation;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;

import com.exactprosystems.clearth.automation.exceptions.AutomationException;
import com.exactprosystems.clearth.automation.exceptions.ParametersException;

import com.exactprosystems.clearth.automation.report.ActionReportWriter;
import com.exactprosystems.clearth.automation.report.ReportsWriter;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;

import com.exactprosystems.clearth.ClearThCore;
import com.exactprosystems.clearth.automation.report.ReportException;
import com.exactprosystems.clearth.automation.report.Result;
import com.exactprosystems.clearth.automation.report.results.DefaultResult;
import com.exactprosystems.clearth.automation.steps.AskForContinue;
import com.exactprosystems.clearth.automation.steps.Default;
import com.exactprosystems.clearth.automation.steps.Sleep;
import com.exactprosystems.clearth.utils.ExceptionUtils;
import com.exactprosystems.clearth.utils.ObjectWrapper;
import com.exactprosystems.clearth.utils.Utils;
import com.exactprosystems.clearth.xmldata.XmlMatrixInfo;
import com.exactprosystems.clearth.xmldata.XmlSchedulerLaunchInfo;

import static java.lang.String.format;
import static org.apache.commons.lang.StringUtils.isNotEmpty;

public abstract class Executor extends Thread
{
	protected static final String format = "HH:mm:ss",
			REPORTDIR_COMPLETED = "completed",
			REPORTDIR_ACTIONS = "actions";
	protected static final SimpleDateFormat hmsFormatter = new SimpleDateFormat(format);

	protected final Scheduler scheduler;
	protected final List<Step> steps;
	protected final List<Matrix> matrices;
	protected final SchedulerStatus status;
	protected final GlobalContext globalContext;
	protected final FailoverStatus failoverStatus;
	protected final Map<String, Preparable> preparableActions;
	protected final ActionParamsCalculator paramsCalculator;
	protected final ActionExecutor actionExecutor;

	private Map<String, String> fixedIds = null;  //Contains action IDs fixed for MVEL so that they can start with digits or underscores
	
	private AtomicBoolean terminated = new AtomicBoolean(false), //Must be set on interruption: on terminate() method call and on throw of InterruptedException
			interrupted = new AtomicBoolean(false),
			paused = new AtomicBoolean(false);
	
	private final SchedulerSuspension suspension = new SchedulerSuspension(false, false, false);
	private Step currentStep;
	private boolean restored = false;
	
	//This is used to highlight idle mode
	private boolean idle;
	
	private Date started, ended;
	private final SimpleDateFormat df = new SimpleDateFormat("yyyyMMddHHmmssSSS");
	protected String reportsDir, specificDir, completedReportsDir, actionsReportsDir, realTimeReportsDir;
	protected ReportsInfo lastReportsInfo = null;
	private Object executionMonitor = null;
	protected File storedActionsReportsDir;

	private Timer sleepTimer = null;
	private volatile long startTimeStep = 0L;

	public Executor(Scheduler scheduler, List<Step> steps, List<Matrix> matrices, GlobalContext globalContext,
			FailoverStatus failoverStatus, Map<String, Preparable> preparableActions)
	{
		super(scheduler.getName());
		
		this.scheduler = scheduler;
		this.steps = steps;
		this.matrices = matrices;
		this.status = scheduler.getStatus();
		this.globalContext = globalContext;
		this.failoverStatus = failoverStatus;
		this.preparableActions = preparableActions;
		this.paramsCalculator = createParamsCalculator();
		this.actionExecutor = createActionExecutor();
	}

	protected abstract Logger getLogger();
	
	protected abstract boolean createConnections() throws Exception;
	protected abstract boolean loadMappingsAndSettings() throws Exception;
	protected abstract StepImpl createStepImpl(String stepKind, String stepParameter);
	protected abstract void closeConnections();
	
	protected abstract void prepareToTryAgainMain();
	protected abstract void prepareToTryAgainAlt();

	protected abstract ReportsWriter initReportsWriter(String pathToStoreReports, String pathToActionsReports);


	@Override
	public void run()
	{
		try
		{
			Logger logger = getLogger();

			currentStep = null;
			suspension.setSuspended(false);
			//terminated = false;

			started = Calendar.getInstance().getTime();
			scheduler.setLastExecutorStartedTime(started);
			getLogger().info("Start of scheduler execution");
			ended = null;
			
			//Let's put reports into public directory: all images inside a report will be downloadable
			specificDir = scheduler.getForUser() + "/" + scheduler.getName() + "/" + df.format(started) + "/";
			reportsDir = ClearThCore.getInstance().getReportsPath() + specificDir; //Not File.separator, because browser will transform "\" into unexpected character
			completedReportsDir = reportsDir + REPORTDIR_COMPLETED + "/";
			actionsReportsDir = reportsDir + REPORTDIR_ACTIONS + "/";
			
			new File(ClearThCore.appRootRelative(reportsDir)).mkdirs();  //Explicitly creating directory for parts of reports so that it will be available in all further calls
			
			getLogger().info("Version: " + ClearThCore.getInstance().getVersion());
			
			if (!createConnections())
				return;
			
			if (!loadMappingsAndSettings())
				return;
			
			if (!prepareActions())
				return;
			
			if (!restored)
			{
				for (Step step : steps)
					step.init();
				
				storeStepNames();
				paramsCalculator.init(matrices);
			}
			else
				restoreActionsReports();

			for (Matrix matrix : matrices)
				evaluateMatrixInfo(matrix);
			
			if (!interrupted.get())
			{
				status.add("Executing steps and actions...");
				globalContext.setStarted(started);
				for (Step step : steps)
				try
				{
					//Is step already done?
					if (step.getFinished()!=null)
						continue;
					
					startTimeStep = 0L;
					if (logger.isTraceEnabled()) {
						logger.trace("Step " + step.getName() + " started");
					}
					
					currentStep = step;
					
					waitForStepStart(step);
					if (interrupted.get())
						break;
					
					if (paused.get())
					{
						step.pause();
					}
						
					BooleanObject replay = new BooleanObject(false);
					do
					{
						if (replay.getValue())
						{
							step.initBeforeReplay();
							for (Matrix m : matrices)
							{
								if (m.isStepSuccessful(step.getName()))  //Step is successful, no need to replay it
									continue;
								
								m.setStepSuccessful(step.getName(), true);  //Will get here only if m.isStepSuccessful() is already set and is false
								m.setSuccessful(true);
								for (String sn : m.getStepSuccess().keySet())
									if ((!sn.equals(step.getName())) && (!m.isStepSuccessful(sn)))
									{
										m.setSuccessful(false);
										break;
									}
										
								if (m.isSuccessful())
									for (Action action : m.getActions())  //Checking if matrix was failed due to one of actions not from current step
										if ((!step.getActions().contains(action)) && (action.getResult()!=null) && (!action.getResult().isSuccess()))
										{
											m.setSuccessful(false);
											break;
										}
							}
						}
						
						step.setStarted(Calendar.getInstance().getTime());
						
						checkStepFileName(step, steps);
						
						StepImpl stepImpl = null;
						if (step.isExecute())
						{
							stepImpl = createStepImpl(step);
							if (stepImpl != null)
							{
								try
								{
									stepImpl.doBeforeActions(globalContext);
								}
								catch (Exception e)
								{
									logger.error(format("Step '%s' (kind: %s) preparation failed.", step.getName(), step.getKind()), e);
									if (e instanceof AutomationException)
										status.add(format("Step '%s' preparation failed: %s", step.getName(), e.getMessage()));
									else // RuntimeException
										status.add(format("Internal error occurred while preparation of step '%s'. See logs for details.", step.getName()));
									step.setFinished(Calendar.getInstance().getTime());
									continue;
								}
							}
						}
						
						//Need to "execute actions" even if step is not executable, because actions may need to set some parameters referenced by further actions
						step.executeActions(actionExecutor, actionsReportsDir, replay, suspension);
						
						if (interrupted.get())
						{
							step.setFinished(Calendar.getInstance().getTime());
							break;
						}
						
						if (!step.isExecute())
						{
							step.setFinished(Calendar.getInstance().getTime());
							continue;
						}
						
						Result stepResult = null;
						if (stepImpl != null)
						{
							try
							{
								stepResult = stepImpl.execute(step.getStepContexts(), globalContext);
							}
							catch (Exception e)
							{
								getLogger().warn("Step '" + step.getName() + "' thrown exception during execution", e);
								stepResult = DefaultResult.failed(e);
							}
						}
						
						step.setFinished(Calendar.getInstance().getTime());
						if (stepResult!=null)
						{
							step.setSuccessful(stepResult.isSuccess());
							step.setStatusComment(stepResult.getComment());
							step.setResult(stepResult);
							if ((stepResult.getError()!=null) && (!(stepResult.getError() instanceof InterruptedException)))
								step.setError(stepResult.getError());
							
							if (!stepResult.isSuccess())
								for (Matrix m : matrices)
									for (Action action : m.getActions())
										if (step.getActions().contains(action))
										{
											m.setStepSuccessful(step.getName(), false);
											break;
										}
						}
						
						if ((stepResult!=null) && (stepResult.getError()!=null) && (stepResult.getError() instanceof InterruptedException))
							interrupted.set(true);
						else if (step.isAskForContinue() || step.isAskIfFailed() && step.getActionsSuccessful() < step.getActionsDone())
						{
							ended = Calendar.getInstance().getTime();
							lastReportsInfo = null;
							try
							{
								synchronized (suspension)
								{
									suspension.setReplayStep(replay.getValue());  //Will show "Replay step" button in GUI
									suspension.setSuspended(true);
									suspension.wait();
									replay.setValue(suspension.isReplayStep());
								}
							}
							catch (InterruptedException e)
							{
								getLogger().error("Interrupted wait while asking for continue", e);
								interrupted.set(true);
								synchronized (suspension)
								{
									suspension.setReplayStep(false);
									suspension.setSuspended(false);
								}
							}
						}
					}
					while ((!interrupted.get()) && (suspension.isReplayStep()));
					
					step.setFinished(Calendar.getInstance().getTime());
					
					if (logger.isDebugEnabled()) {
						logger.debug("Step " + step.getName() + " finished");
					}
					
					if (interrupted.get())
						break;
				}
				finally
				{
					stepFinished(step);
				}
				
				if (!interrupted.get())
				{
					waitForAsyncActions();
					status.add("Execution finished");
				}
				else
					status.add("Execution interrupted");
			}
			
			ended = Calendar.getInstance().getTime();

			//Forming reports
			status.add("Making reports...");
			makeReports(completedReportsDir, actionsReportsDir);
			status.add("Reports made");
			
			//Storing info about this launch to make user able to access it from GUI
			Date finished = Calendar.getInstance().getTime();
			globalContext.setFinished(finished);
			getLogger().info("Execution of the scheduler completed now");
			XmlSchedulerLaunchInfo launchInfo = buildXmlSchedulerLaunchInfo(finished);
			scheduler.addLaunch(launchInfo);
			
			status.add("Finished");
		}
		catch (Exception e)
		{
			getLogger().error("FATAL error occurred", e);
			status.add("FATAL ERROR: "+(e instanceof ParametersException ? e.getMessage() : ExceptionUtils.getDetailedMessage(e)));
			status.add("See log for details");
		}
		finally
		{
			terminated.set(true);

			//Disposing all connections
			disposeConnections();

			if (executionMonitor!=null)
				synchronized (executionMonitor)
				{
					executionMonitor.notify();
				}
			
			//Let scheduler know about execution end so that the scheduler can free this thread's resources.
			scheduler.executorFinished();

			clearMatricesContexts();
			clearGlobalContexts();

			this.matrices.clear();
			if(scheduler.executor != null) // We can't clear steps if it is executor created in seqExec
				steps.clear();

			if (sleepTimer != null) {
				sleepTimer.cancel();
				sleepTimer = null;
			}
			Utils.closeResource(actionExecutor);
		}
	}

	protected void stepFinished(Step step)
	{
		step.clearContexts(); // step.getStepContext(matrix).clearContext(); can be done if it is executor created in seqExec
		step.actions.clear();
	}

	public void clearMatricesContexts()
	{
		matrices.forEach(matrix -> matrix.getContext().clearContext());
	}

	public void clearGlobalContexts()
	{
		globalContext.clearContext();
	}

	private void disposeConnections()
	{
		try
		{
			closeConnections();
		} catch (Exception e)
		{
			getLogger().error("Exception during closing connections.", e);
		}
	}

	protected void evaluateMatrixInfo(Matrix matrix) throws Exception
	{
		MatrixFunctions functions = globalContext.getMatrixFunctions();
		Map<String, String> constants = matrix.getConstants(),
				formulas = matrix.getFormulas();
		
		Map<String, Object> mvelVars = matrix.getMvelVars();
		if (isNotEmpty(matrix.getDescription()))
		{
			try
			{
				String desc = functions.calculateExpression(matrix.getDescription(), Matrix.DESCRIPTION,
				                                            mvelVars, null, null, new ObjectWrapper(0)).toString();
				matrix.setDescription(desc);
			}
			catch (Exception e)
			{
				throw new ParametersException("Could not calculate expressions in description of matrix '" + matrix.getName() + "'", e);
			}
		}
		
		if (formulas == null)
			return;
		
		Map<String, String> references = (Map<String, String>)mvelVars.get(Matrix.MATRIX);
		//Evaluating matrix constants if they contain expressions
		for (Entry<String, String> f : formulas.entrySet())
		{
			try
			{
				String value = functions.calculateExpression(f.getValue(), f.getKey(),
						mvelVars, null, null, new ObjectWrapper(0)).toString();
				constants.put(f.getKey(), value);
				references.put(f.getKey(), value);
			}
			catch (Exception e)
			{
				throw new ParametersException("Could not calculate value of '" + f.getKey() + "' constant in matrix '" + matrix.getName() + "'", e);
			}
		}
	}
	
	
	protected ActionParamsCalculator createParamsCalculator()
	{
		return new ActionParamsCalculator(globalContext.getMatrixFunctions());
	}
	
	protected ActionReportWriter createReportWriter()
	{
		return new ActionReportWriter();
	}
	
	protected ActionExecutor createActionExecutor()
	{
		return new ActionExecutor(globalContext, paramsCalculator, createReportWriter(), failoverStatus);
	}
	
	protected void waitForAsyncActions()
	{
		if (!interrupted.get())
		{
			try
			{
				actionExecutor.waitForSchedulerAsyncActions();
			}
			catch (InterruptedException e)
			{
				getLogger().warn("Wait for scheduler async actions interrupted", e);
			}
		}
		
		actionExecutor.checkAsyncActions();
	}
	
	
	/**
	 * If step name doesn't comply with requirements for file's name in Windows invalid characters are changed to "_".
	 * The resulting name is saved in step as safeName and used as step's file name 
	 * and as part of container name in Freemarker templates.
	 * If another step has the same safeName this name is complemented by "_" character in the end of name. 
	 * @param step - current step
	 * @param steps - all steps
	 */
	protected void checkStepFileName(Step step, List<Step> steps)
	{
		String validName = Step.getValidFileName(step.getSafeName());
		if (!validName.equals(step.getSafeName()))
		{
			while(true)
			{
				boolean valid = true;
				for (Step st : steps )
				{
					if (st.getSafeName().equals(validName))
					{
						valid = false;
						validName += "_";
						break;
					}
				}
				if (valid)
				{
					step.setSafeName(validName);
					break;
				}
			}
		}
	}
	
	protected boolean prepareActions() throws Exception
	{
		if (preparableActions != null)
			for (Preparable action : preparableActions.values())
				action.prepare(globalContext, status);
		return true;
	}

	protected XmlSchedulerLaunchInfo buildXmlSchedulerLaunchInfo(Date finished)
	{
		XmlSchedulerLaunchInfo launchInfo = ClearThCore.getInstance().getSchedulerFactory().createSchedulerLaunchInfo();
		launchInfo.setStarted(started);
		launchInfo.setFinished(finished);
		launchInfo.setInterrupted(interrupted.get());
		launchInfo.setReportsPath(specificDir + REPORTDIR_COMPLETED);
		launchInfo.getMatricesInfo().addAll(lastReportsInfo.getMatrices());
		boolean successfulRun = true;
		for (XmlMatrixInfo matrixInfo : launchInfo.getMatricesInfo())
			if (!matrixInfo.isSuccessful())
			{
				successfulRun = false;
				break;
			}
		launchInfo.setSuccess(successfulRun);
		return launchInfo;
	}
	
	protected StepImpl createStepImpl(Step step)
	{
		StepImpl stepImpl;
		switch (CoreStepKind.stepKindByLabel(step.getKind()))
		{
			case Default :
				stepImpl = new Default();
				break;
			case Sleep :
				stepImpl = new Sleep();
				stepImpl.addParameter("sleep", step.getParameter());
				break;
			case AskForContinue :
				stepImpl = new AskForContinue();
				stepImpl.addParameter("suspended", suspension);
				break;
			default :
				stepImpl = createStepImpl(step.getKind(), step.getParameter());
				if (stepImpl==null)
				{
					getLogger().warn("Unknown step kind: '{}'", step.getKind());
				}
		}
		return stepImpl;
	}


	public List<Step> getSteps()
	{
		return steps;
	}
	
	public List<Matrix> getMatrices()
	{
		return matrices;
	}
	
	public String getStartedByUser()
	{
		return globalContext.getStartedByUser();
	}
	
	public Date getBusinessDay()
	{
		return globalContext.getCurrentDate();
	}
	
	public boolean isWeekendHoliday()
	{
		return globalContext.isWeekendHoliday();
	}
	
	public Map<String, Boolean> getHolidays()
	{
		return globalContext.getHolidays();
	}
	

	public void interruptExecution()
	{
		interrupted.set(true);
		
		if (currentStep!=null)
		{
			currentStep.interruptExecution();
			
			if (currentStep.isPaused())
			{
				synchronized (suspension)
				{
					suspension.setSuspended(false);
					suspension.notify();
				}
			}
		}
		
		actionExecutor.interruptExecution();
		interrupt();
	}
	
	public void pauseExecution()
	{
		synchronized (suspension)
		{
			if (suspension.isTimeout())
			{
				suspension.setSuspended(true);
				return;
			}
		}
		this.paused.set(true);
		
		if (currentStep!=null)
		{
			currentStep.pause();
		}
	}
	
	
	public boolean isExecutionInterrupted()
	{
		return interrupted.get();
	}

	public boolean isTerminated()
	{
		return terminated.get();
	}

	public Step getCurrentStep()
	{
		return currentStep;
	}
	
	public boolean isCurrentStepIdle()
	{
		return idle;
	}

	public boolean isSuspended()
	{
		return suspension.isSuspended();
	}
	
	public boolean isReplayEnabled()
	{
		return suspension.isReplayStep();
	}

	public void continueExecution()
	{
		if (paused.get())
			paused.set(false);
		
		synchronized (suspension)
		{
			suspension.setReplayStep(false);
			suspension.setSuspended(false);
			if (!suspension.isTimeout())
			{
				suspension.notify();
			}
		}
	}
	
	public void replayStep()
	{
		synchronized (suspension)
		{
			suspension.setReplayStep(true);  //Will cause step replay in do..while of run()
			suspension.setSuspended(false);
			suspension.notify();
		}
	}
	
	
	public boolean isFailover()
	{
		return failoverStatus.failover;
	}
	
	private void tryAgain()
	{
		failoverStatus.failover = false;
		failoverStatus.notify();
	}
	
	public void tryAgainMain()
	{
		synchronized (failoverStatus)
		{
			prepareToTryAgainMain();
			tryAgain();
		}
	}
	
	public void tryAgainAlt()
	{
		synchronized (failoverStatus)
		{
			prepareToTryAgainAlt();
			tryAgain();
		}
	}
	
	public int getFailoverReason()
	{
		if (!failoverStatus.failover)
			return ActionType.NONE;
		
		return failoverStatus.reason;
	}

	public int getFailoverActionType()
	{
		if (!failoverStatus.failover)
			return ActionType.NONE;
		
		return failoverStatus.actionType;
	}
	
	public void setFailoverRestartAction(boolean needRestart)
	{
		synchronized (failoverStatus)
		{
			failoverStatus.needRestartAction = needRestart;
			tryAgain();
		}
	}
	
	public boolean isRestored()
	{
		return restored;
	}
	
	public void setRestored(boolean restored)
	{
		this.restored = restored;
	}
	
	
	public Date getStarted()
	{
		return started;
	}

	public void setStarted(Date started)
	{
		this.started = started;
	}
	
	
	public Date getEnded()
	{
		return ended;
	}

	public void setEnded(Date ended)
	{
		this.ended = ended;
	}
	
	
	public Map<String, String> getFixedIds()
	{
		return fixedIds;
	}
	
	public void setFixedIds(Map<String, String> fixedIds)
	{
		this.fixedIds = fixedIds;
	}
	
	
	public String getReportsDir()
	{
		return reportsDir;
	}
	
	public String getCompletedReportsDir()
	{
		return completedReportsDir;
	}
	
	
	public void setExecutionMonitor(Object executionMonitor)
	{
		this.executionMonitor = executionMonitor;
	}
	
	
	protected void backupDir(File source, File target) throws IOException
	{
		if (source.isDirectory())
		{
			if (!target.exists())
				target.mkdirs();

			File entries[] = source.listFiles();
			for (File entry : entries) 
				backupDir(entry, new File(target, entry.getName()));
		}
		else
			com.exactprosystems.clearth.utils.FileOperationUtils.copyFile(source.getCanonicalPath(), target.getCanonicalPath());
	}
	
	protected void storeStepNames()
	{
		File stepNamesFile = new File(ClearThCore.appRootRelative(reportsDir), "steps");
		PrintWriter stepNamesWriter = null;
		try
		{
			stepNamesWriter = new PrintWriter(stepNamesFile);
			for (Step step : steps)
				stepNamesWriter.println(step.getName());
		}
		catch (IOException e)
		{
			getLogger().warn("Could not store step names list to '"+stepNamesFile.getAbsolutePath()+"'. Automatic report restoration won't work for this run", e);
		}
		finally
		{
			Utils.closeResource(stepNamesWriter);
		}
	}
	
	protected Step searchPreviousStep(Step step) {
		ListIterator<Step> stepListIterator = steps.listIterator(steps.size());
		while (stepListIterator.hasPrevious() && !(step.equals(stepListIterator.previous())));
		Step prevStep = null;
		while (stepListIterator.hasPrevious() && !(prevStep = stepListIterator.previous()).isExecute());
		return prevStep == null || !prevStep.isExecute() ? null : prevStep;
	}

	protected Step searchFirstExecutableStep() {
		for (Step step: steps) {
			if (step.isExecute() && step.getStarted() != null) {
				return step;
			}
		}
		return null;
	}
	
	protected boolean isStepExecutable(Step step)
	{
		if ((step.isExecute()) && (step.getActions() != null) && (step.getActions().size() > 0))
		{
			for (Action action : step.getActions())
			{
				if (action.isExecutable())
					return true;
			}
		}
		return false;
	}
	
	protected void waitForStepStart(Step step)
	{
		if (isStepExecutable(step) && (step.getStartAt() != null) && (!step.getStartAt().isEmpty()))
		{
			Calendar now = Calendar.getInstance(), 
					startTime = Calendar.getInstance();
			Logger logger = getLogger();

			try
			{
				idle = true;
				boolean relative = step.getStartAt().startsWith("+");
				String start = relative ? step.getStartAt().substring(1) : step.getStartAt();
				if (start.length() == 5)
					start += ":00";
				if (relative)
				{
					Step prevStep;
					switch (step.getStartAtType())
					{
						case END_STEP:
							prevStep = searchPreviousStep(step);
							if (prevStep != null)
								now.setTime(prevStep.getFinished());
							break;
						case START_STEP:
							prevStep = searchPreviousStep(step);
							if (prevStep != null)
								now.setTime(prevStep.getStarted());
							break;
						case START_SCHEDULER:
							now.setTime(globalContext.getStarted());
							break;
						case START_EXECUTION:
							prevStep = searchFirstExecutableStep();
							if (prevStep != null)
								now.setTime(prevStep.getStarted());
							else
								now.setTime(globalContext.getStarted());
							break;
					}

					String[] parts = start.split(":");
					int[] intParts = new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]) };
					startTime = (Calendar) now.clone();
					startTime.set(Calendar.HOUR_OF_DAY, now.get(Calendar.HOUR_OF_DAY) + intParts[0]);
					startTime.set(Calendar.MINUTE, now.get(Calendar.MINUTE) + intParts[1]);
					startTime.set(Calendar.SECOND, now.get(Calendar.SECOND) + intParts[2]);
				}
				else
				{
					startTime.setTime(hmsFormatter.parse(start));
					startTime.set(Calendar.YEAR, now.get(Calendar.YEAR));
					startTime.set(Calendar.DAY_OF_YEAR, now.get(Calendar.DAY_OF_YEAR));
				}

				boolean disableWait = false;
				if (startTime.getTimeInMillis() <= now.getTimeInMillis())
				{
					if (step.isWaitNextDay())
						startTime.set(Calendar.DAY_OF_YEAR, startTime.get(Calendar.DAY_OF_YEAR) + 1);
					else
					{
						disableWait = true;
						logger.debug("Executing next step without waiting");
					}
				}
				startTimeStep = startTime.getTimeInMillis();
				
				if (!disableWait)
				{
					logger.debug("Waiting for next step until " + startTime.getTime());
					if (sleepTimer == null)
					{
						sleepTimer = new Timer();
						logger.debug("Timer created");
					}
					TimerTask task = new UnsleepTask();
					sleepTimer.schedule(task, startTime.getTime());

					synchronized (suspension)
					{
						suspension.setTimeout(true);
						suspension.wait();
					}

					task.cancel();
					logger.trace("Finished waiting for next step");
				}
			}
			catch (Exception e)
			{
				if (e instanceof InterruptedException)
				{
					getLogger().error("Wait for step start interrupted", e);
					interrupted.set(true);
				}
				else
				{
					String msg = "Step '" + step.getName() + "': error while parsing 'Start at' parameter (" + step.getStartAt() + "), it must be in format '" + format + "' with optional '+' in the beginning";
					status.add(msg);
					getLogger().warn(msg, e);
				}
			}
			finally
			{
				idle = false;
			}
		}
	}

	//FIXME: this method is not thread-safe
	protected void makeReports(String pathToStoreReports, String pathToActionsReports) throws IOException, ReportException
	{
		pathToStoreReports = ClearThCore.appRootRelative(pathToStoreReports); //AppRootRelative because we don't want to download file, we want to see it in browser
		pathToActionsReports = ClearThCore.appRootRelative(pathToActionsReports);
		new File(pathToStoreReports).mkdirs();

		ReportsWriter reportsWriter = initReportsWriter(pathToStoreReports, pathToActionsReports);

		for (Matrix matrix : matrices)
		{
			List<String> stepsMatrix = getMatrixSteps(matrix.getShortFileName());

			reportsWriter.buildAndWriteReports(matrix, stepsMatrix, globalContext.getStartedByUser(), started, ended);
		}
		
		lastReportsInfo = new ReportsInfo();
		lastReportsInfo.setPath(pathToStoreReports);
		List<XmlMatrixInfo> mi = lastReportsInfo.getMatrices();
		for (Matrix matrix : matrices)
		{
			XmlMatrixInfo matrixInfo = new XmlMatrixInfo();
			matrixInfo.setName(matrix.getName());
			matrixInfo.setFileName(matrix.getShortFileName());
			matrixInfo.setActionsDone(matrix.getActionsDone());
			matrixInfo.setSuccessful(matrix.isSuccessful());
			
			mi.add(matrixInfo);
		}
	}
	
	/**
	 * @return map which contains pairs: matrix name - list of matrix step names 
	 */
	public static Map<String, List<String>> getStepsByMatricesMap(File actionsReports)
	{
		//actionsReports contains directories per matrix with set of steps as sub-files (action reports) within
		File[] maReports = actionsReports.listFiles(new FileFilter()
		{
			@Override
			public boolean accept(File pathname)
			{
				return pathname.isDirectory();
			}
		});
		
		Map<String, List<String>> matrSteps = new HashMap<String, List<String>>();
		if (maReports != null) {
			for (File ma : maReports) {
				String[] steps = ma.list();
				if (steps != null)
					matrSteps.put(ma.getName(), Arrays.asList(steps));
			}
		}
		return matrSteps;
	}
	
	public void makeCurrentReports(String pathToStoreReports)
	{
		try
		{
			makeReports(pathToStoreReports, actionsReportsDir);
		}
		catch (Exception e)
		{
			getLogger().warn("Error while making reports", e);
		}
	}
	
	public void copyActionReports (File toDir)
	{
		try
		{
			FileUtils.deleteDirectory(toDir);
			FileUtils.copyDirectory(new File (ClearThCore.appRootRelative(actionsReportsDir)),
					toDir);
		}
		catch (Exception e)
		{
			getLogger().warn("Error while copying actions directory", e);
		}
	}
	
	public void setStoredActionReports (File srcDir)
	{
		this.storedActionsReportsDir = srcDir;
	}
	
	protected void restoreActionsReports() throws IOException
	{
		if (storedActionsReportsDir != null && storedActionsReportsDir.exists())
		{
			FileUtils.copyDirectory(storedActionsReportsDir,
					new File(ClearThCore.appRootRelative(actionsReportsDir)));
		}
		else
		{
			getLogger().warn("Stored actions reports were not found. Directory {} does not exist", storedActionsReportsDir);
		}
	}
	
	public ReportsInfo getLastReportsInfo()
	{
		return lastReportsInfo;
	}
	
	public void clearLastReportsInfo()
	{
		this.lastReportsInfo = null;
	}

	public String getActionsReportsDir()
	{
		return actionsReportsDir;
	}

	public long getStartTimeStep() {
		return startTimeStep;
	}

	public void skipWaitingStep()  {
		this.startTimeStep = 0;
		suspension.setTimeout(false);
	}

	public boolean isSuspensionTimeout() {
		return suspension.isTimeout();
	}

	public abstract List<String> getMatrixSteps(String matrixName);

	protected class UnsleepTask extends TimerTask {
		@Override
		public void run() {
			synchronized (suspension) {
				suspension.setTimeout(false);
				if (!suspension.isSuspended()) {
					suspension.notify();
				}
			}
		}
	}
}