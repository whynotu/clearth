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

package com.exactprosystems.clearth.messages;

import java.util.Map;

import com.exactprosystems.clearth.ClearThCore;
import com.exactprosystems.clearth.automation.actions.MessageAction;
import com.exactprosystems.clearth.automation.exceptions.ResultException;
import com.exactprosystems.clearth.connectivity.ConnectivityException;
import com.exactprosystems.clearth.connectivity.ListenerType;
import com.exactprosystems.clearth.connectivity.ReceiveListener;
import com.exactprosystems.clearth.connectivity.connections.ClearThConnection;
import com.exactprosystems.clearth.connectivity.connections.ClearThMessageConnection;
import com.exactprosystems.clearth.connectivity.listeners.ClearThMessageCollector;
import com.exactprosystems.clearth.utils.inputparams.InputParamsUtils;

/**
 * Class that finds connections and collectors by given name or action parameters.
 * @author vladimir.panarin
 */
public class ConnectionFinder
{
	public String getConnectionName(Map<String, String> inputParams)
	{
		return InputParamsUtils.getRequiredString(inputParams, MessageAction.CONNECTIONNAME);
	}
	
	public ClearThMessageConnection<?,?> findConnection(String connectionName) throws ResultException
	{
		try
		{
			ClearThConnection<?,?> con = ClearThCore.connectionStorage().findRunningConnection(connectionName);
			if (ClearThMessageConnection.isMessageConnection(con))
				return (ClearThMessageConnection<?,?>)con;
			else
				throw ResultException.failed("Connection '" + connectionName + "' is not suitable for processing messages!");
		}
		catch (ConnectivityException e)
		{
			throw ResultException.failed(e.getMessage());
		}
	}
	
	public ClearThMessageConnection<?,?> findConnection(Map<String, String> inputParams) throws ResultException
	{
		String conName = getConnectionName(inputParams);
		return findConnection(conName);
	}
	
	
	public ClearThMessageCollector findCollector(ClearThMessageConnection<?,?> connection) throws ResultException
	{
		String label = ListenerType.Collector.getLabel();
		ReceiveListener listener = connection.findListener(label);
		if (listener == null)
			throw ResultException.failed("Listener with type '" + label + "' is not added to specified connection");
		
		return (ClearThMessageCollector)listener;
	}
	
	public ClearThMessageCollector findCollector(Map<String, String> inputParams) throws ResultException
	{
		ClearThMessageConnection<?,?> con = findConnection(inputParams);
		return findCollector(con);
	}
}