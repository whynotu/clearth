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

package com.exactprosystems.clearth.newproject.web.beans;

import com.exactprosystems.clearth.ConfigFiles;
import com.exactprosystems.clearth.utils.ClearThException;
import com.exactprosystems.clearth.web.beans.ClearThCoreApplicationBean;
import com.exactprosystems.clearth.newproject.Application;

public class ApplicationBean extends ClearThCoreApplicationBean
{
	private String svnRevisionInfo;

	public ApplicationBean() throws ClearThException
	{
		super();
	}

	@Override
	protected void initApplication() throws ClearThException
	{
		System.out.println("Starting ClearTH");
		new Application().init(configFiles, deploymentConfig);
		svnRevisionInfo = Application.getInstance().getVersion().toString();
	}

	@Override
	protected ConfigFiles createConfigFiles()
	{
		return new ConfigFiles("clearth.cfg");
	}

	public String getSvnRevisionInfo()
	{
		return svnRevisionInfo;
	}
}
