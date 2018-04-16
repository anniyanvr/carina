/*******************************************************************************
 * Copyright 2013-2018 QaProSoft (http://www.qaprosoft.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.qaprosoft.carina.core.foundation.webdriver.core.factory;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.events.WebDriverEventListener;
import org.testng.Reporter;

import com.qaprosoft.carina.core.foundation.commons.SpecialKeywords;
import com.qaprosoft.carina.core.foundation.utils.Configuration;
import com.qaprosoft.carina.core.foundation.utils.Configuration.Parameter;
import com.qaprosoft.carina.core.foundation.webdriver.core.factory.impl.DesktopFactory;
import com.qaprosoft.carina.core.foundation.webdriver.core.factory.impl.MobileFactory;
import com.qaprosoft.carina.core.foundation.webdriver.device.Device;
import com.qaprosoft.carina.core.foundation.webdriver.listener.IConfigurableEventListener;
import com.qaprosoft.zafira.client.ZafiraSingleton;
import com.qaprosoft.zafira.models.dto.TestArtifactType;

/**
 * DriverFactory produces driver instance with desired capabilities according to
 * configuration.
 *
 * @author Alexey Khursevich (hursevich@gmail.com)
 */
public class DriverFactory {

	protected static final Logger LOGGER = Logger.getLogger(DriverFactory.class);

	public static WebDriver create(String testName, Device device, DesiredCapabilities capabilities,
			String seleniumHost) {
		LOGGER.debug("DriverFactory start...");
		AbstractFactory factory;

		String driverType = Configuration.getDriverType(capabilities);
		switch (driverType) {
		case SpecialKeywords.DESKTOP:
			factory = new DesktopFactory();
			break;

		case SpecialKeywords.MOBILE:
			factory = new MobileFactory();
			break;

		default:
			throw new RuntimeException("Unsupported driver_type: " + driverType);
		}

		WebDriver driver = factory.registerListeners(factory.create(testName, device, capabilities, seleniumHost),
				getEventListeners());
		LOGGER.debug("DriverFactory finish...");

		if (driver instanceof RemoteWebDriver && ((RemoteWebDriver) driver).getCapabilities().is("enableVNC")) {
			streamVNC((RemoteWebDriver) driver, seleniumHost);
		}

		return driver;
	}

	/**
	 * Reads 'driver_event_listeners' configuration property and initializes
	 * appropriate array of driver event listeners.
	 * 
	 * @return array of driver listeners
	 */
	private static WebDriverEventListener[] getEventListeners() {
		List<WebDriverEventListener> listeners = new ArrayList<>();
		try {
			String listenerClasses = Configuration.get(Parameter.DRIVER_EVENT_LISTENERS);
			if (!StringUtils.isEmpty(listenerClasses)) {
				for (String listenerClass : listenerClasses.split(",")) {
					Class<?> clazz = Class.forName(listenerClass);
					if (IConfigurableEventListener.class.isAssignableFrom(clazz)) {
						IConfigurableEventListener listener = (IConfigurableEventListener) clazz.newInstance();
						if (listener.enabled()) {
							listeners.add(listener);
							LOGGER.debug("Webdriver event listener registered: " + clazz.getName());
						}
					}
				}
			}
		} catch (Exception e) {
			LOGGER.error("Unable to register webdriver event listeners: " + e.getMessage());
		}
		return listeners.toArray(new WebDriverEventListener[listeners.size()]);
	}

	/**
	 * Creates Zafira artifact that contains link to VNC websocket
	 * 
	 * @param driver
	 *            - {@link RemoteWebDriver} instance
	 * @param seleniumHost
	 *            - Selenium server host
	 */
	private static void streamVNC(RemoteWebDriver driver, String seleniumHost) {
		try {
			if (Reporter.getCurrentTestResult().getAttribute("ztid") != null && ZafiraSingleton.INSTANCE.isRunning()) {

				TestArtifactType artifact = new TestArtifactType();
				artifact.setName("Live demo");
				artifact.setTestId((Long) Reporter.getCurrentTestResult().getAttribute("ztid"));

				// User STF or Selenoid websocket
				if (driver.getCapabilities().getCapability("vnc") != null) {
					artifact.setLink(
							String.format("ws://%s/websockify", driver.getCapabilities().getCapability("vnc")));
				} else {
					// TODO: resolve negative case when VNC is not supported
					artifact.setLink(String.format("%s/vnc/%s", seleniumHost, driver.getSessionId().toString()));
				}

				ZafiraSingleton.INSTANCE.getClient().addTestArtifact(artifact);
			}
		} catch (Exception e) {
			LOGGER.error("Unable to stream VNC: " + e.getMessage(), e);
		}
	}
}