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
package com.qaprosoft.carina.core.foundation.listeners;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Category;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.testng.Assert;
import org.testng.ISuite;
import org.testng.ITestContext;
import org.testng.ITestResult;
import org.testng.SkipException;
import org.testng.TestListenerAdapter;
import org.testng.TestNG;
import org.testng.xml.XmlClass;
import org.testng.xml.XmlInclude;
import org.testng.xml.XmlSuite;
import org.testng.xml.XmlTest;

import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.qaprosoft.amazon.AmazonS3Manager;
import com.qaprosoft.carina.core.foundation.commons.SpecialKeywords;
import com.qaprosoft.carina.core.foundation.jira.Jira;
import com.qaprosoft.carina.core.foundation.report.Artifacts;
import com.qaprosoft.carina.core.foundation.report.ReportContext;
import com.qaprosoft.carina.core.foundation.report.TestResultItem;
import com.qaprosoft.carina.core.foundation.report.TestResultType;
import com.qaprosoft.carina.core.foundation.report.email.EmailReportGenerator;
import com.qaprosoft.carina.core.foundation.report.email.EmailReportItemCollector;
import com.qaprosoft.carina.core.foundation.skip.ExpectedSkipManager;
import com.qaprosoft.carina.core.foundation.utils.Configuration;
import com.qaprosoft.carina.core.foundation.utils.Configuration.Parameter;
import com.qaprosoft.carina.core.foundation.utils.DateUtils;
import com.qaprosoft.carina.core.foundation.utils.JsonUtils;
import com.qaprosoft.carina.core.foundation.utils.Messager;
import com.qaprosoft.carina.core.foundation.utils.R;
import com.qaprosoft.carina.core.foundation.utils.metadata.MetadataCollector;
import com.qaprosoft.carina.core.foundation.utils.metadata.model.ElementsInfo;
import com.qaprosoft.carina.core.foundation.utils.resources.I18N;
import com.qaprosoft.carina.core.foundation.utils.resources.L10N;
import com.qaprosoft.carina.core.foundation.utils.resources.L10Nparser;
import com.qaprosoft.carina.core.foundation.webdriver.CarinaDriver;
import com.qaprosoft.carina.core.foundation.webdriver.TestPhase;
import com.qaprosoft.carina.core.foundation.webdriver.TestPhase.Phase;
import com.qaprosoft.carina.core.foundation.webdriver.core.capability.CapabilitiesLoader;
import com.qaprosoft.carina.core.foundation.webdriver.device.Device;
import com.qaprosoft.carina.core.foundation.webdriver.device.DevicePool;
import com.qaprosoft.hockeyapp.HockeyAppManager;

/*
 * AbstractTest - base test for UI and API tests.
 * 
 * @author Alex Khursevich
 */
public class CarinaListener extends AbstractTestListener {
    protected static final Logger LOGGER = Logger.getLogger(CarinaListener.class);

    protected static final long EXPLICIT_TIMEOUT = Configuration.getLong(Parameter.EXPLICIT_TIMEOUT);

    protected static final String SUITE_TITLE = "%s%s%s - %s (%s%s)";
    protected static final String XML_SUITE_NAME = " (%s)";
    
    protected static boolean initialized = false;
    
    @Override
    public void onStart(ITestContext context) {
        super.onStart(context);
        
        // move below code to synchronized block to init it at once.
        // in comparison with @BeforeSuite testNG annotation it can be executed several times depending on suite xml connt
        if (initialized) {
            //TODO: [VD] remove below message later 
            LOGGER.info("Do nothing as onStart/BeforeSuite already initialized.");
            return;
        }
        synchronized (this) {
            initialized = true;

            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new ShutdownHook());
            // Set log4j properties
            PropertyConfigurator.configure(ClassLoader.getSystemResource("log4j.properties"));
    
            try {
                Logger root = Logger.getRootLogger();
                Enumeration<?> allLoggers = root.getLoggerRepository().getCurrentCategories();
                while (allLoggers.hasMoreElements()) {
                    Category tmpLogger = (Category) allLoggers.nextElement();
                    if (tmpLogger.getName().equals("com.qaprosoft.carina.core")) {
                        tmpLogger.setLevel(Level.toLevel(Configuration.get(Parameter.CORE_LOG_LEVEL)));
                    }
                }
            } catch (NoSuchMethodError e) {
                LOGGER.error("Unable to redefine logger level due to the conflicts between log4j and slf4j!");
            }
    
            LOGGER.info(Configuration.asString());
            // Configuration.validateConfiguration();
    
            LOGGER.debug("Default thread_count=" + context.getCurrentXmlTest().getSuite().getThreadCount());
            context.getCurrentXmlTest().getSuite().setThreadCount(Configuration.getInt(Parameter.THREAD_COUNT));
            LOGGER.debug("Updated thread_count=" + context.getCurrentXmlTest().getSuite().getThreadCount());
    
            // update DataProviderThreadCount if any property is provided otherwise sync with value from suite xml file
            int count = Configuration.getInt(Parameter.DATA_PROVIDER_THREAD_COUNT);
            if (count > 0) {
                LOGGER.debug("Updated 'data_provider_thread_count' from "
                        + context.getCurrentXmlTest().getSuite().getDataProviderThreadCount() + " to " + count);
                context.getCurrentXmlTest().getSuite().setDataProviderThreadCount(count);
            } else {
                LOGGER.debug("Synching data_provider_thread_count with values from suite xml file...");
                R.CONFIG.put(Parameter.DATA_PROVIDER_THREAD_COUNT.getKey(),
                        String.valueOf(context.getCurrentXmlTest().getSuite().getDataProviderThreadCount()));
                LOGGER.debug("Updated 'data_provider_thread_count': " + Configuration.getInt(Parameter.DATA_PROVIDER_THREAD_COUNT));
            }
    
            LOGGER.debug("Default data_provider_thread_count="
                    + context.getCurrentXmlTest().getSuite().getDataProviderThreadCount());
            LOGGER.debug("Updated data_provider_thread_count="
                    + context.getCurrentXmlTest().getSuite().getDataProviderThreadCount());
    
            try {
                L10N.init();
            } catch (Exception e) {
                LOGGER.error("L10N bundle is not initialized successfully!", e);
            }
    
            try {
                I18N.init();
            } catch (Exception e) {
                LOGGER.error("I18N bundle is not initialized successfully!", e);
            }
    
            try {
                L10Nparser.init();
            } catch (Exception e) {
                LOGGER.error("L10Nparser bundle is not initialized successfully!", e);
            }
    
            // TODO: move out from AbstractTest->executeBeforeTestSuite
            String customCapabilities = Configuration.get(Parameter.CUSTOM_CAPABILITIES);
            if (!customCapabilities.isEmpty()) {
                // redefine core CONFIG properties using custom capabilities file
                new CapabilitiesLoader().loadCapabilities(customCapabilities);
            }
    
            String extraCapabilities = Configuration.get(Parameter.EXTRA_CAPABILITIES);
            if (!extraCapabilities.isEmpty()) {
                // redefine core CONFIG properties using extra capabilities file
                new CapabilitiesLoader().loadCapabilities(extraCapabilities);
            }
    
            updateAppPath();
            
            onHealthCheck(context.getSuite());
        }
    }
    
    @Override
    public void beforeConfiguration(ITestResult result) {
        super.beforeConfiguration(result);
        // remember active test phase to organize valid driver pool manipulation process 
        if (result.getMethod().isBeforeSuiteConfiguration()) {
            TestPhase.setActivePhase(Phase.BEFORE_SUITE);
        }
        
        if (result.getMethod().isBeforeClassConfiguration()) {
            TestPhase.setActivePhase(Phase.BEFORE_CLASS);
            //TODO: analyze cases when AfterClass is not declared inside test java class
            // maybe move into the BEFORE_CLASS
            ConcurrentHashMap<String, CarinaDriver> currentDrivers = getDrivers();
            // 1. quit all Phase.BEFORE_CLASS drivers for current thread as it is new configuration call/class 
            for (Map.Entry<String, CarinaDriver> entry : currentDrivers.entrySet()) {
                CarinaDriver drv = entry.getValue();
                if (Phase.BEFORE_CLASS.equals(drv.getPhase())) {
                    quitDriver(entry.getKey());
                }
            }
        }
        
        if (result.getMethod().isBeforeMethodConfiguration()) {
            TestPhase.setActivePhase(Phase.BEFORE_METHOD);
            
            //TODO: test use-case with dependency
            LOGGER.debug("Deinitialize unused driver(s) on before test method start.");
            ConcurrentHashMap<String, CarinaDriver> currentDrivers = getDrivers();
            // 1. quit all Phase.METHOD drivers for current thread
            for (Map.Entry<String, CarinaDriver> entry : currentDrivers.entrySet()) {
                CarinaDriver drv = entry.getValue();
                if (Phase.METHOD.equals(drv.getPhase())) {
                    quitDriver(entry.getKey());
                }
            }
        }
        
        if (result.getMethod().isAfterMethodConfiguration()) {
            TestPhase.setActivePhase(Phase.AFTER_METHOD);
        }
        
        if (result.getMethod().isAfterClassConfiguration()) {
            TestPhase.setActivePhase(Phase.AFTER_CLASS);
            //TODO: analyze cases when AfterClass is not declared inside test java class
            // maybe move into the BEFORE_CLASS
            ConcurrentHashMap<String, CarinaDriver> currentDrivers = getDrivers();
            // 1. quit all Phase.BEFORE_CLASS drivers for current thread as it is new configuration call/class 
            for (Map.Entry<String, CarinaDriver> entry : currentDrivers.entrySet()) {
                CarinaDriver drv = entry.getValue();
                if (Phase.BEFORE_CLASS.equals(drv.getPhase())) {
                    quitDriver(entry.getKey());
                }
            }
        }
        
        if (result.getMethod().isAfterSuiteConfiguration()) {
            TestPhase.setActivePhase(Phase.AFTER_SUITE);
        }
        
    }

    @Override
    public void onTestStart(ITestResult result) {
        TestPhase.setActivePhase(Phase.METHOD);
        String[] dependedUponMethods = result.getMethod().getMethodsDependedUpon();
        
        if (dependedUponMethods.length == 0) {
            ConcurrentHashMap<String, CarinaDriver> currentDrivers = getDrivers();
            // 1. quit all Phase.METHOD drivers for current thread
            for (Map.Entry<String, CarinaDriver> entry : currentDrivers.entrySet()) {
                CarinaDriver drv = entry.getValue();
                if (Phase.METHOD.equals(drv.getPhase())) {
                    quitDriver(entry.getKey());
                }
                
                // all before_method drivers move into METHOD to be able to quit them on next onTestStart!
                if (Phase.BEFORE_METHOD.equals(drv.getPhase())) {
                    drv.setPhase(Phase.METHOD);
                }
            }
        }
        
        // handle expected skip
        Method testMethod = result.getMethod().getConstructorOrMethod().getMethod();
        if (ExpectedSkipManager.getInstance().isSkip(testMethod, result.getTestContext())) {
            skipExecution("Based on rule listed above");
        }

        
        super.onTestStart(result);
        
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        onTestFinish(result);
        super.onTestSuccess(result);
    }
    
    @Override
    public void onTestFailure(ITestResult result) {
        onTestFinish(result);
        super.onTestFailure(result);
    }
    
    @Override
    public void onTestSkipped(ITestResult result) {
        onTestFinish(result);
        super.onTestSkipped(result);        
    }
        
    public void onTestFinish(ITestResult result) {
        try {
            // TODO: improve later removing duplicates with AbstractTestListener
            // handle Zafira already passed exception for re-run and do nothing. maybe return should be enough
            if (result.getThrowable() != null && result.getThrowable().getMessage() != null
                    && result.getThrowable().getMessage().startsWith(SpecialKeywords.ALREADY_PASSED)) {
                // [VD] it is prohibited to release TestInfoByThread in this place.!
                return;
            }

            // handle AbstractTest->SkipExecution
            if (result.getThrowable() != null && result.getThrowable().getMessage() != null
                    && result.getThrowable().getMessage().startsWith(SpecialKeywords.SKIP_EXECUTION)) {
                // [VD] it is prohibited to release TestInfoByThread in this place.!
                return;
            }

            List<String> tickets = Jira.getTickets(result);
            result.setAttribute(SpecialKeywords.JIRA_TICKET, tickets);
            Jira.updateAfterTest(result);

            // we shouldn't deregister info here as all retries will not work
            // TestNamingUtil.releaseZafiraTest();

            // clear jira tickets to be sure that next test is not affected.
            Jira.clearTickets();

            Artifacts.clearArtifacts();

        } catch (Exception e) {
            LOGGER.error("Exception in AbstractTest->executeAfterTestMethod!", e);
        }

    }

    @Override
    public void onFinish(ITestContext context) {
        super.onFinish(context);
        try {
            //forcibly quit all drivers
            quitDrivers();

            ReportContext.removeTempDir(); // clean temp artifacts directory
            //HtmlReportGenerator.generate(ReportContext.getBaseDir().getAbsolutePath());

            String browser = getBrowser();
            String deviceName = getDeviceName();
            // String suiteName = getSuiteName(context);
            String title = getTitle(context);

            TestResultType testResult = EmailReportGenerator.getSuiteResult(EmailReportItemCollector.getTestResults());
            String status = testResult.getName();

            title = status + ": " + title;

            String env = "";
            if (!Configuration.isNull(Parameter.ENV)) {
                env = Configuration.get(Parameter.ENV);
            }

            if (!Configuration.get(Parameter.URL).isEmpty()) {
                env += " - <a href='" + Configuration.get(Parameter.URL) + "'>" + Configuration.get(Parameter.URL) + "</a>";
            }

            ReportContext.getTempDir().delete();

            // Update JIRA
            Jira.updateAfterSuite(context, EmailReportItemCollector.getTestResults());

            LOGGER.debug("Generating email report...");
            
            // Generate emailable html report using regular method
            EmailReportGenerator report = new EmailReportGenerator(title, env,
                    Configuration.get(Parameter.APP_VERSION), deviceName,
                    browser, DateUtils.now(),
                    EmailReportItemCollector.getTestResults(),
                    EmailReportItemCollector.getCreatedItems());

            String emailContent = report.getEmailBody();
            // Store emailable report under emailable-report.html
            ReportContext.generateHtmlReport(emailContent);

            printExecutionSummary(EmailReportItemCollector.getTestResults());

            TestResultType suiteResult = EmailReportGenerator.getSuiteResult(EmailReportItemCollector.getTestResults());
            switch (suiteResult) {
            case SKIP_ALL:
                Assert.fail("All tests were skipped! Analyze logs to determine possible configuration issues.");
                break;
            case SKIP_ALL_ALREADY_PASSED:
                LOGGER.info("Nothing was executed in rerun mode because all tests already passed and registered in Zafira Repoting Service!");
                break;
            default:
                // do nothing
            }
            LOGGER.debug("Finish email report generation.");

        } catch (Exception e) {
            LOGGER.error("Exception in AbstractTest->executeAfterSuite.", e);
        } finally {
            //do nothing
        }

    }

    // TODO: remove this private method
    private String getDeviceName() {
        String deviceName = "Desktop";

        if (!DevicePool.getDevice().isNull()) {
            // Samsung - Android 4.4.2; iPhone - iOS 7
            Device device = DevicePool.getDevice();
            String deviceTemplate = "%s - %s %s";
            deviceName = String.format(deviceTemplate, device.getName(), device.getOs(), device.getOsVersion());
        }

        return deviceName;
    }

    protected String getBrowser() {
        String browser = "";
        if (!Configuration.get(Parameter.BROWSER).isEmpty()) {
            browser = Configuration.get(Parameter.BROWSER);
        }

        return browser;
    }

    protected String getTitle(ITestContext context) {
        String browser = getBrowser();
        if (!browser.isEmpty()) {
            browser = " " + browser; // insert the space before
        }
        String device = getDeviceName();

        String env = !Configuration.isNull(Parameter.ENV) ? Configuration.get(Parameter.ENV) : Configuration.get(Parameter.URL);

        String title = "";
        String app_version = "";

        if (!Configuration.get(Parameter.APP_VERSION).isEmpty()) {
            // if nothing is specified then title will contain nothing
            app_version = Configuration.get(Parameter.APP_VERSION) + " - ";
        }

        String suiteName = getSuiteName(context);
        String xmlFile = getSuiteFileName(context);

        title = String.format(SUITE_TITLE, app_version, suiteName, String.format(XML_SUITE_NAME, xmlFile), env, device, browser);

        return title;
    }

    private String getSuiteFileName(ITestContext context) {
        // TODO: investigate why we need such method and suite file name at all
        String fileName = context.getSuite().getXmlSuite().getFileName();
        if (fileName == null) {
            fileName = "undefined";
        }
        LOGGER.debug("Full suite file name: " + fileName);
        if (fileName.contains("\\")) {
            fileName = fileName.replaceAll("\\\\", "/");
        }
        fileName = StringUtils.substringAfterLast(fileName, "/");
        LOGGER.debug("Short suite file name: " + fileName);
        return fileName;
    }

    protected String getSuiteName(ITestContext context) {

        String suiteName = "";

        if (context.getSuite().getXmlSuite() != null && !"Default suite".equals(context.getSuite().getXmlSuite().getName())) {
            suiteName = Configuration.get(Parameter.SUITE_NAME).isEmpty() ? context.getSuite().getXmlSuite().getName()
                    : Configuration.get(Parameter.SUITE_NAME);
        } else {
            suiteName = Configuration.get(Parameter.SUITE_NAME).isEmpty() ? R.EMAIL.get("title") : Configuration.get(Parameter.SUITE_NAME);
        }

        return suiteName;
    }

    private void printExecutionSummary(List<TestResultItem> tris) {
        Messager.INROMATION
                .info("**************** Test execution summary ****************");
        int num = 1;
        for (TestResultItem tri : tris) {
            String failReason = tri.getFailReason();
            if (failReason == null) {
                failReason = "";
            }

            if (!tri.isConfig() && !failReason.contains(SpecialKeywords.ALREADY_PASSED)
                    && !failReason.contains(SpecialKeywords.SKIP_EXECUTION)) {
                String reportLinks = !StringUtils.isEmpty(tri.getLinkToScreenshots())
                        ? "screenshots=" + tri.getLinkToScreenshots() + " | "
                        : "";
                reportLinks += !StringUtils.isEmpty(tri.getLinkToLog()) ? "log=" + tri.getLinkToLog() : "";
                Messager.TEST_RESULT.info(String.valueOf(num++), tri.getTest(), tri.getResult().toString(),
                        reportLinks);
            }
        }
    }

    /**
     * Redefine Jira tickets from test.
     *
     * @param tickets to set
     */
    @Deprecated
    protected void setJiraTicket(String... tickets) {
        List<String> jiraTickets = new ArrayList<String>();
        for (String ticket : tickets) {
            jiraTickets.add(ticket);
        }
        Jira.setTickets(jiraTickets);
    }

    protected void putS3Artifact(String key, String path) {
        AmazonS3Manager.getInstance().put(Configuration.get(Parameter.S3_BUCKET_NAME), key, path);
    }

    protected S3Object getS3Artifact(String bucket, String key) {
        return AmazonS3Manager.getInstance().get(Configuration.get(Parameter.S3_BUCKET_NAME), key);
    }

    protected S3Object getS3Artifact(String key) {
        return getS3Artifact(Configuration.get(Parameter.S3_BUCKET_NAME), key);
    }

    private void updateAppPath() {

        try {
            if (!Configuration.get(Parameter.ACCESS_KEY_ID).isEmpty()) {
                updateS3AppPath();
            }
        } catch (Exception e) {
            LOGGER.error("AWS S3 manager exception detected!", e);
        }

        try {
            if (!Configuration.get(Parameter.HOCKEYAPP_TOKEN).isEmpty()) {
                updateHockeyAppPath();
            }
        } catch (Exception e) {
            LOGGER.error("HockeyApp manager exception detected!", e);
        }

    }

    /**
     * Method to update MOBILE_APP path in case if apk is located in Hockey App.
     */
    private void updateHockeyAppPath() {
        // hockeyapp://appName/platformName/buildType/version
        Pattern HOCKEYAPP_PATTERN = Pattern
                .compile("hockeyapp:\\/\\/([a-zA-Z-0-9][^\\/]*)\\/([a-zA-Z-0-9][^\\/]*)\\/([a-zA-Z-0-9][^\\/]*)\\/([a-zA-Z-0-9][^\\/]*)");
        String mobileAppPath = Configuration.getMobileApp();
        Matcher matcher = HOCKEYAPP_PATTERN.matcher(mobileAppPath);

        LOGGER.info("Analyzing if mobile_app is located on HockeyApp...");
        if (matcher.find()) {
            LOGGER.info("app artifact is located on HockeyApp...");
            String appName = matcher.group(1);
            String platformName = matcher.group(2);
            String buildType = matcher.group(3);
            String version = matcher.group(4);

            String hockeyAppLocalStorage = Configuration.get(Parameter.HOCKEYAPP_LOCAL_STORAGE);
            // download file from HockeyApp to local storage

            File file = HockeyAppManager.getInstance().getBuild(hockeyAppLocalStorage, appName, platformName, buildType, version);

            Configuration.setMobileApp(file.getAbsolutePath());

            LOGGER.info("Updated mobile app: " + Configuration.getMobileApp());

            // try to redefine app_version if it's value is latest or empty
            String appVersion = Configuration.get(Parameter.APP_VERSION);
            if (appVersion.equals("latest") || appVersion.isEmpty()) {
                R.CONFIG.put(Parameter.APP_VERSION.getKey(), file.getName());
            }
        }

    }

    /**
     * Method to update MOBILE_APP path in case if apk is located in s3 bucket.
     */
    private void updateS3AppPath() {
        Pattern S3_BUCKET_PATTERN = Pattern.compile("s3:\\/\\/([a-zA-Z-0-9][^\\/]*)\\/(.*)");
        // get app path to be sure that we need(do not need) to download app from s3 bucket
        String mobileAppPath = Configuration.getMobileApp();
        Matcher matcher = S3_BUCKET_PATTERN.matcher(mobileAppPath);

        LOGGER.info("Analyzing if mobile app is located on S3...");
        if (matcher.find()) {
            LOGGER.info("app artifact is located on s3...");
            String bucketName = matcher.group(1);
            String key = matcher.group(2);
            Pattern pattern = Pattern.compile(key);

            // analyze if we have any pattern inside mobile_app to make extra
            // search in AWS
            int position = key.indexOf(".*");
            if (position > 0) {
                // /android/develop/dfgdfg.*/Mapmyrun.apk
                int slashPosition = key.substring(0, position).lastIndexOf("/");
                if (slashPosition > 0) {
                    key = key.substring(0, slashPosition);
                    S3ObjectSummary lastBuild = AmazonS3Manager.getInstance().getLatestBuildArtifact(bucketName, key,
                            pattern);
                    key = lastBuild.getKey();
                }

            }

            S3Object objBuild = AmazonS3Manager.getInstance().get(bucketName, key);

            String s3LocalStorage = Configuration.get(Parameter.S3_LOCAL_STORAGE);

            // download file from AWS to local storage

            String fileName = s3LocalStorage + "/" + StringUtils.substringAfterLast(objBuild.getKey(), "/");
            File file = new File(fileName);

            // verify maybe requested artifact with the same size was already
            // download
            if (file.exists() && file.length() == objBuild.getObjectMetadata().getContentLength()) {
                LOGGER.info("build artifact with the same size already downloaded: " + file.getAbsolutePath());
            } else {
                LOGGER.info(String.format("Following data was extracted: bucket: %s, key: %s, local file: %s",
                        bucketName, key, file.getAbsolutePath()));
                AmazonS3Manager.getInstance().download(bucketName, key, new File(fileName));
            }

            Configuration.setMobileApp(file.getAbsolutePath());

            // try to redefine app_version if it's value is latest or empty
            String appVersion = Configuration.get(Parameter.APP_VERSION);
            if (appVersion.equals("latest") || appVersion.isEmpty()) {
                R.CONFIG.put(Parameter.APP_VERSION.getKey(), file.getName());
            }

        }
    }

    protected void skipExecution(String message) {
        throw new SkipException(SpecialKeywords.SKIP_EXECUTION + ": " + message);
    }

    protected void onHealthCheck(ISuite suite) {
        String healthCheckClass = Configuration.get(Parameter.HEALTH_CHECK_CLASS);
        if (suite.getParameter(Parameter.HEALTH_CHECK_CLASS.getKey()) != null) {
            // redefine by suite arguments as they have higher priority
            healthCheckClass = suite.getParameter(Parameter.HEALTH_CHECK_CLASS.getKey());
        }

        String healthCheckMethods = Configuration.get(Parameter.HEALTH_CHECK_METHODS);
        if (suite.getParameter(Parameter.HEALTH_CHECK_METHODS.getKey()) != null) {
            // redefine by suite arguments as they have higher priority
            healthCheckMethods = suite.getParameter(Parameter.HEALTH_CHECK_METHODS.getKey());
        }

        String[] healthCheckMethodsArray = null;
        if (!healthCheckMethods.isEmpty()) {
            healthCheckMethodsArray = healthCheckMethods.split(",");
        }
        checkHealth(suite, healthCheckClass, healthCheckMethodsArray);
    }
    

    private void checkHealth(ISuite suite, String className, String[] methods) {

        if (className.isEmpty()) {
            return;
        }

        // create runtime XML suite for health check
        XmlSuite xmlSuite = new XmlSuite();
        xmlSuite.setName("HealthCheck XmlSuite - " + className);

        XmlTest xmlTest = new XmlTest(xmlSuite);
        xmlTest.setName("HealthCheck TestCase");
        XmlClass xmlHealthCheckClass = new XmlClass();
        xmlHealthCheckClass.setName(className);

        // TestNG do not execute missed methods so we have to calulate expected methods count to handle potential mistakes in methods naming
        int expectedMethodsCount = -1;
        if (methods != null) {
            // declare particular methods if they are provided
            List<XmlInclude> methodsToRun = constructIncludes(methods);
            expectedMethodsCount = methodsToRun.size();
            xmlHealthCheckClass.setIncludedMethods(methodsToRun);
        }

        xmlTest.setXmlClasses(Arrays.asList(new XmlClass[] { xmlHealthCheckClass }));
        xmlSuite.setTests(Arrays.asList(new XmlTest[] { xmlTest }));

        LOGGER.info("HealthCheck '" + className + "' is started.");
        LOGGER.debug("HealthCheck suite content:" + xmlSuite.toXml());

        // Second TestNG process to run HealthCheck
        TestNG testng = new TestNG();
        testng.setXmlSuites(Arrays.asList(xmlSuite));

        TestListenerAdapter tla = new TestListenerAdapter();
        testng.addListener(tla);

        testng.run();
        synchronized (this) {
            boolean passed = false;
            if (expectedMethodsCount == -1) {
                if (tla.getPassedTests().size() > 0 && tla.getFailedTests().size() == 0
                        && tla.getSkippedTests().size() == 0) {
                    passed = true;
                }
            } else {
                LOGGER.info("Expected passed tests count: " + expectedMethodsCount);
                if (tla.getPassedTests().size() == expectedMethodsCount && tla.getFailedTests().size() == 0
                        && tla.getSkippedTests().size() == 0) {
                    passed = true;
                }
            }
            if (passed) {
                LOGGER.info("HealthCheck suite '" + className + "' is finished successfully.");
            } else {
                throw new SkipException("Skip test(s) due to health check failures for '" + className + "'");
            }
        }
    }

    private List<XmlInclude> constructIncludes(String... methodNames) {
        List<XmlInclude> includes = new ArrayList<XmlInclude>();
        for (String eachMethod : methodNames) {
            includes.add(new XmlInclude(eachMethod));
        }
        return includes;
    }

    public static class ShutdownHook extends Thread {

        private static final Logger LOGGER = Logger.getLogger(ShutdownHook.class);

        private void generateMetadata() {
            Map<String, ElementsInfo> allData = MetadataCollector.getAllCollectedData();
            if (allData.size() > 0) {
                LOGGER.debug("Generating collected metadada start...");
            }
            for (String key : allData.keySet()) {
                LOGGER.debug("Creating... medata for '" + key + "' object...");
                File file = new File(ReportContext.getArtifactsFolder().getAbsolutePath() + "/metadata/" + key.hashCode() + ".json");
                PrintWriter out = null;
                try {
                    out = new PrintWriter(file);
                    out.append(JsonUtils.toJson(MetadataCollector.getAllCollectedData().get(key)));
                    out.flush();
                } catch (FileNotFoundException e) {
                    LOGGER.error("Unable to write metadata to json file: " + file.getAbsolutePath(), e);
                } finally {
                    if (out != null) {
                        out.close();
                    }
                }
                LOGGER.debug("Created medata for '" + key + "' object...");
            }

            if (allData.size() > 0) {
                LOGGER.debug("Generating collected metadada finish...");
            }
        }

        @Override
        public void run() {
            LOGGER.debug("Running shutdown hook");
            generateMetadata();
        }

    }

}