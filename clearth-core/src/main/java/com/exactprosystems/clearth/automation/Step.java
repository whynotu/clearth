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

import com.csvreader.CsvReader;
import com.csvreader.CsvWriter;
import com.exactprosystems.clearth.automation.exceptions.FailoverException;
import com.exactprosystems.clearth.automation.report.Result;
import org.slf4j.Logger;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.exactprosystems.clearth.automation.ActionExecutor.isAsyncAction;

public abstract class Step
{
	protected String name, kind, startAt, parameter;
	protected boolean askForContinue, askIfFailed, execute;
	protected String comment = "";
	
	protected List<Action> actions = new ArrayList<Action>();
	protected String actionsReportsDir = null;

	protected Date started, finished;
	protected int actionsDone = 0, actionsSuccessful = 0;
	protected boolean successful = true, interrupted = false, paused = false;
	protected SchedulerSuspension suspension = null;
	protected Map<Matrix, StepContext> stepContexts = null;
	protected String statusComment = null;
	protected Throwable error = null;
	protected Result result = null;

	protected StartAtType startAtType = StartAtType.DEFAULT;
	protected boolean waitNextDay = false;
	protected String safeName = null;
	
	protected boolean actionPause = false;
	protected String actionPauseDescription = null;


	public Step()
	{
		name = null;
		safeName = null;
		kind = null;
	}
	
	public Step(String name, String kind, String startAt, StartAtType startAtType, boolean waitNextDay, String parameter, boolean askForContinue, boolean askIfFailed, boolean execute, String comment)
	{
		this.name = name;
		safeName = name;
		this.kind = kind;
		this.startAt = startAt;
		this.parameter = parameter;
		this.askForContinue = askForContinue;
		this.askIfFailed = askIfFailed;
		this.execute = execute;
		this.startAtType = startAtType;
		this.waitNextDay = waitNextDay;
		setComment(comment);
	}
	
	public Step(CsvReader reader) throws IOException
	{
		assignStepFields(reader);
	}

	public void assignStepFields(CsvReader reader) throws IOException
	{
		name = reader.get(StepParams.GLOBAL_STEP.getValue());
		safeName = name;
		kind = reader.get(StepParams.STEP_KIND.getValue());
		startAt	= reader.get(StepParams.START_AT.getValue());
		setStartAtTypeString(reader.get(StepParams.START_AT_TYPE.getValue()));
		parameter	= reader.get(StepParams.PARAMETER.getValue());
		setComment(reader.get(StepParams.COMMENT.getValue()));

		String waitNextD = reader.get(StepParams.WAIT_NEXT_DAY.getValue()),
				askForCont = reader.get(StepParams.ASK_FOR_CONTINUE.getValue()),
				askIfFld	= reader.get(StepParams.ASK_IF_FAILED.getValue()),
				exec	= reader.get(StepParams.EXECUTE.getValue());
		waitNextDay = !waitNextD.isEmpty() && !waitNextD.equals("0");
		askForContinue = !askForCont.isEmpty() && !askForCont.equals("0");
		askIfFailed = !askIfFld.isEmpty() && !askIfFld.equals("0");
		execute = !exec.isEmpty() && !exec.equals("0");
	}
	
	protected abstract Logger getLogger();
	
	public abstract void init();
	public abstract void initBeforeReplay();
	
	protected abstract void beforeActions(GlobalContext globalContext);
	protected abstract void beforeAction(Action action, StepContext stepContext, MatrixContext matrixContext, GlobalContext globalContext);
	protected abstract void actionFailover(Action action, FailoverException e, StepContext stepContext, MatrixContext matrixContext, GlobalContext globalContext);
	protected abstract void afterAction(Action action, StepContext stepContext, MatrixContext matrixContext, GlobalContext globalContext);
	protected abstract void afterActions(GlobalContext globalContext, Map<Matrix, StepContext> stepContexts);

	public String getName()
	{
		return name;
	}
	
	public void setName(String name)
	{
		this.name = name;
	}
	
		
	public String getSafeName()
	{
		return safeName;
	}

	public void setSafeName(String fileName)
	{
		this.safeName = fileName;
	}

	public String getKind()
	{
		return kind;
	}
	
	public void setKind(String kind)
	{
		this.kind = kind;
	}
	
	
	public String getStartAt()
	{
		return startAt;
	}
	
	public void setStartAt(String startAt)
	{
		if (startAt != null)
			this.startAt = startAt.trim();
		else
			this.startAt = null;
	}
	
	public StartAtType getStartAtType()
	{
		return startAtType;
	}
	
	
	public String getStartAtTypeString()
	{
		return startAtType.getStringType();
	}

	public void setStartAtTypeString(String startAtType)
	{
		this.startAtType = StartAtType.getValue(startAtType);
	}
	
	
	public boolean isWaitNextDay()
	{
		return this.waitNextDay;
	}
	
	public void setWaitNextDay(boolean waitNextDay)
	{
		this.waitNextDay = waitNextDay;
	}
	
	
	public String getParameter()
	{
		return parameter;
	}
	
	public void setParameter(String parameter)
	{
		this.parameter = parameter;
	}


	public boolean isAskForContinue()
	{
		return askForContinue;
	}

	public void setAskForContinue(boolean askForContinue)
	{
		this.askForContinue = askForContinue;
	}


	public boolean isAskIfFailed()
	{
		return askIfFailed;
	}

	public void setAskIfFailed(boolean askIfFailed)
	{
		this.askIfFailed = askIfFailed;
	}


	public boolean isExecute()
	{
		return execute;
	}
	
	public void setExecute(boolean execute)
	{
		this.execute = execute;
	}

	public String getComment()
	{
		return comment;
	}

	public void setComment(String comment)
	{
		if(comment != null)
			comment = comment.replaceAll("[\r\n\t]+"," ");
		this.comment = comment;
	}
	
	public List<Action> getActions()
	{
		return actions;
	}
	
	public void setActions(List<Action> actions)
	{
		this.actions = actions;
	}
	
	public String getActionsReportsDir()
	{
		return actionsReportsDir;
	}


	public Date getStarted()
	{
		return started;
	}
	
	public void setStarted(Date started)
	{
		this.started = started;
	}
	
	
	public Date getFinished()
	{
		return finished;
	}
	
	public void setFinished(Date finished)
	{
		this.finished = finished;
	}
	
	
	public int getActionsDone()
	{
		return actionsDone;
	}
	
	public void setActionsDone(int actionsDone)
	{
		this.actionsDone = actionsDone;
	}
	
	
	public int getActionsSuccessful()
	{
		return actionsSuccessful;
	}
	
	public void setActionsSuccessful(int actionsSuccessful)
	{
		this.actionsSuccessful = actionsSuccessful;
	}
	
	
	public boolean isSuccessful()
	{
		return successful;
	}
	
	public void setSuccessful(boolean successful)
	{
		this.successful = successful;
	}
	
	
	public void interruptExecution()
	{
		interrupted = true;
	}
	
	public boolean isInterrupted()
	{
		return interrupted;
	}
		
	
	public boolean isPaused()
	{
		return paused;
	}

	public void pause()
	{
		this.paused = true;
	}

	public String getStatusComment()
	{
		return statusComment;
	}
	
	public void setStatusComment(String statusComment)
	{
		this.statusComment = statusComment;
	}
	
	
	public Throwable getError()
	{
		return error;
	}
	
	public void setError(Throwable error)
	{
		this.error = error;
		if(error != null)
		{
			if (isSuccessful())
				setSuccessful(false);
		}
	}

	public Result getResult() {
		return result;
	}

	public void setResult(Result result) {
		this.result = result;
	}


	public Map<Matrix, StepContext> getStepContexts()
	{
		return stepContexts;
	}
	
	public void setStepContexts(Map<Matrix, StepContext> stepContexts)
	{
		this.stepContexts = stepContexts;
	}

	public boolean isActionPause()
	{
		return actionPause;
	}

	public String getActionPauseDescription()
	{
		return actionPauseDescription;
	}

	public void save(CsvWriter writer) throws IOException
	{
		writer.write(name, true);
		writer.write(kind, true);
		writer.write(startAt, true);
		writer.write(startAtType.getStringType(), true);
		writer.write(waitNextDay ? "1" : "0", true);
		writer.write(parameter, true);
		writer.write(askForContinue ? "1" : "0", true);
		writer.write(askIfFailed ? "1" : "0", true);
		writer.write(execute ? "1" : "0", true);
		writer.write(comment, true);
	}
	

	public static String getValidFileName(String fileName) {
		return fileName.replaceAll("[.\\\\/:*?\"<>|']", "_");
	}

	protected void pauseStep()
	{
		try
		{
			synchronized (suspension)
			{
				suspension.setReplayStep(false);
				suspension.setSuspended(true);
				suspension.wait();
				
				this.paused = false;
			}
		}
		catch (InterruptedException e)
		{
			getLogger().error("Wait interrupted", e);
		}
	}
	
	protected void pauseAction(String pauseDescription)
	{
		actionPauseDescription = pauseDescription;
		actionPause = true;
		pauseStep();
	}
	
	protected void checkContextsExist()
	{
		if (stepContexts == null)
			stepContexts = new LinkedHashMap<Matrix, StepContext>();
	}
	
	protected StepContext createStepContext(String stepName, Date started)
	{
		return new StepContext(stepName, started);
	}
	
	protected StepContext getStepContext(Matrix matrix)
	{
		checkContextsExist();
		
		StepContext stepContext = stepContexts.get(matrix);
		if (stepContext == null)
		{
			stepContext = createStepContext(this.name, this.started);
			stepContexts.put(matrix, stepContext);
		}
		
		return stepContext;
	}
	
	protected void updateByAsyncActions(ActionExecutor actionExec)
	{
		actionExec.checkAsyncActions();
		actionsSuccessful = actionExec.getActionsSuccessful();
	}
	
	protected void waitForAsyncActions(ActionExecutor actionExec)
	{
		if (!interrupted)
		{
			try
			{
				actionExec.waitForStepAsyncActions(this.getName());
			}
			catch (InterruptedException e)
			{
				getLogger().warn("Wait for async actions interrupted", e);
			}
		}
		
		updateByAsyncActions(actionExec);
	}
	
	
	public void executeActions(ActionExecutor actionExec, String actionsReportsDir, BooleanObject replay, SchedulerSuspension suspension)
	{
		this.actionsReportsDir = actionsReportsDir;
		Logger logger = getLogger();
		checkContextsExist();
		if ((actions == null) || (actions.isEmpty()))
		{
			logger.info("No actions for step " + this.getName());
			return;
		}
		
		GlobalContext globalContext = actionExec.getGlobalContext();
		this.suspension = suspension;
		try
		{
			interrupted = false;
			//paused = false;
			beforeActions(globalContext);
			
			actionExec.reset(actionsReportsDir);  //Resetting all internal counters to prepare execution of actions from this particular step
			
			AtomicBoolean canReplay = new AtomicBoolean(false);
			logger.info("Running actions for step '{}'", this.getName());
			for (Action action : actions)
			{
				try
				{
					if (action.getFinished() != null)  // If we replay the step and this action is already done
						continue;
					
					if (paused)
						this.pauseStep();
					
					if (interrupted)
						break;
					
					actionExec.prepareToAction(action);
					
					Matrix matrix = action.getMatrix();
					MatrixContext matrixContext = matrix.getContext();
					StepContext stepContext = getStepContext(matrix);
					
					beforeAction(action, stepContext, matrixContext, globalContext);
					
					if (replay.getValue() && !actionExec.prepareActionReplay(action))
						continue;
					
					if (this.isExecute())
						actionExec.executeAction(action, stepContext, canReplay);
					
					afterAction(action, stepContext, matrixContext, globalContext);
					updateByAsyncActions(actionExec);
				}
				finally
				{
					if (!isAsyncAction(action))
						action.dispose();
					
					actionsDone = actionExec.getActionsDone();
					actionsSuccessful = actionExec.getActionsSuccessful();
				}
			}

			actionExec.afterActionsExecution(this);
			waitForAsyncActions(actionExec);
			replay.setValue(canReplay.get());
		}
		finally
		{
			afterActions(globalContext, stepContexts);
		}
	}

	public void clearContexts()
	{
		if(stepContexts != null)
		{
			stepContexts.values().forEach(StepContext::clearContext);
			stepContexts.clear();
		}
	}

	public enum StepParams
	{
		GLOBAL_STEP ("Global step"),
		STEP_KIND ("Step kind"),
		START_AT ("Start at"),
		START_AT_TYPE ("Start at type"),
		WAIT_NEXT_DAY("Wait next day"),
		PARAMETER ("Parameter"),
		COMMENT ("Comment"),
		ASK_FOR_CONTINUE ("Ask for continue"),
		ASK_IF_FAILED ("Ask if failed"),
		EXECUTE ("Execute");
		
		private final String value;
		
		StepParams(String value)
		{
			this.value = value;
		}
		
		public String getValue()
		{
			return value;
		}
	}
}