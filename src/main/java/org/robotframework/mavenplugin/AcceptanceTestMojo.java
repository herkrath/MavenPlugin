package org.robotframework.mavenplugin;

/*
 * Copyright 2011 Michael Mallete, Dietrich Schulten
 * Copyright 2013 Nokia Siemens Networks Oyj
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.*;
import java.util.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.codehaus.plexus.util.StringUtils;
import org.robotframework.RobotFramework;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Runs the Robot tests. Behaves like invoking the "jybot" command. The goal will not fail the maven
 * build, rather the results are evaluated by the verify goal. For details see the <a
 * href="http://maven.apache.org/plugins/maven-failsafe-plugin/">maven-failsafe-plugin</a>, which
 * uses the same strategy for integration testing.
 * 
 * Robot Framework tests cases are created in files and directories, and they are executed by
 * configuring the path to the file or directory in question to the testCasesDirectory
 * configuration. The given file or directory creates the top-level tests suites, which gets its
 * name, unless overridden with the "name" option, from the file or directory name.
 *
 * @goal acceptance-test
 * @phase integration-test
 * @requiresDependencyResolution test
 */
public class AcceptanceTestMojo extends AbstractMojoWithLoadedClasspath {

    // TODO better integrate test result into eclipse failure reports
    // enable to open report from eclipse easily after test run
    // as with surefire reports?
    protected void subclassExecute() throws MojoExecutionException, MojoFailureException {

        if (shouldSkipTests()) {
            getLog().info("RobotFramework tests are skipped.");
            return;
        }
        String[] runArguments = generateRunArguments();

        getLog().debug("robotframework arguments: " + StringUtils.join(runArguments, " "));
        evaluateReturnCode(executeRobot(runArguments));
    }

    private int executeRobot(String[] runArguments) throws MojoExecutionException {
        if (externalRunner==null) {
            return  RobotFramework.run(runArguments);
        } else {
            return externalExecute(runArguments);
        }
    }
    
    private int externalExecute(String[] runArguments) throws MojoExecutionException {
        try {
            if (externalRunner.getRunWithPython()) {
                return PythonRunner.exec("robot", runArguments);
            } else {
                return exec(externalRunner.getRunnerClass(), runArguments, externalRunner.getEnvironmentVariables());
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Executing external robot failed.", e);
        } catch (InterruptedException e) {
            throw new MojoExecutionException("Executing external robot failed.", e);
        }
    }

    public int exec(String runnerClass, String[] arguments, Map<String, String> environment) throws IOException,
            InterruptedException {
        runnerClass = runnerClass == null ? RobotFramework.class.getCanonicalName() : runnerClass;
        ProcessBuilder builder = new ProcessBuilder(createExternalCommand(runnerClass, arguments, externalRunner.getJvmArgs()));
        Map<String, String> env =  builder.environment();
        String classpath = externalRunner.getExcludeDependencies() ? getRobotJar() : getClassPathString();
        if (environment.containsKey("CLASSPATH")) {
            classpath = environment.get("CLASSPATH") + File.pathSeparator + classpath;
        }
        env.putAll(environment);
        env.put("CLASSPATH", classpath);
        Process process = builder.start();
        StreamReader stdout = new StreamReader(process.getInputStream());
        StreamReader stderr = new StreamReader(process.getErrorStream());
        stdout.start();
        stderr.start();
        return process.waitFor();
    }

    private List<String> createExternalCommand(String runnerClass, String[] arguments, List<String> jvmArgs) {
        String javaHome = System.getProperty("java.home");
        String javaBin = join(File.separator, javaHome, "bin", "java");
        List<String> cmd = new ArrayList<String>();
        cmd.add(javaBin);
        cmd.addAll(jvmArgs);
        cmd.add(runnerClass);
        cmd.addAll(Arrays.asList(arguments));
        System.out.println("Executing Robot with command:");
        System.out.println(cmd);
        return cmd;
    }

    protected void evaluateReturnCode(int robotRunReturnValue)
        // ======== ==========================================
        // RC Explanation
        // ======== ==========================================
        // 0 All critical tests passed.
        // 1-249 Returned number of critical tests failed.
        // 250 250 or more critical failures.
        // 251 Help or version information printed.
        // 252 Invalid test data or command line options.
        // 253 Test execution stopped by user.
        // 255 Unexpected internal error.
        // ======== ==========================================
            throws MojoFailureException, MojoExecutionException {
        switch (robotRunReturnValue) {
            // case 250:
            // throw new MojoFailureException( robotRunReturnValue
            // + " or more critical test cases failed. Check the logs for details." );
            // case 251:
            // getLog().info( "Help or version information printed. No tests were executed." );
            // break;
            case 252:
                // TODO write log output into message
                writeXunitFileWithError("Invalid test data or command line options (Returncode 252).");
                break;
            case 255:
                writeXunitFileWithError("Unexpected internal error (Returncode 255).");
                break;
            // case 253:
            // getLog().info( "Test execution stopped by user." );
            // break;
            default:
                // do nothing, rely on xunit file created by rf
        }
    }

    private void writeXunitFileWithError(String message) {
        try {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder();
            Document document = builder.newDocument();
            // <testsuite errors="0" failures="5" tests="5" skip="0" name="Robot-Fail">
            Element testsuite = document.createElement("testsuite");
            testsuite.setAttribute("errors", "1");
            testsuite.setAttribute("failures", "0");
            testsuite.setAttribute("tests", "0");
            testsuite.setAttribute("name", getTestSuiteName());
            Element testcase = document.createElement("testcase");
            testcase.setAttribute("classname", "ExecutionError");
            testcase.setAttribute("name", message);

            Element error = document.createElement("error");
            error.setAttribute("message", message);
            testcase.appendChild(error);

            testsuite.appendChild(testcase);
            document.appendChild(testsuite);

            Transformer transformer = TransformerFactory.newInstance()
                    .newTransformer();
            Source xmlSource = new DOMSource(document);
            final File output = makeAbsolute(outputDirectory, xunitFile);
            outputDirectory.mkdirs();
            Result outputTarget = new StreamResult(output);
            transformer.transform(xmlSource, outputTarget);

        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }

    }

    private String getTestSuiteName() {
        final String testSuiteName;
        if (name != null) {
            testSuiteName = name;
        } else {
            String delim = " -_";
            StringTokenizer tokenizer = new StringTokenizer(testCasesDirectory.getName(), delim, true);
            StringBuilder sb = new StringBuilder();
            while (tokenizer.hasMoreTokens()) {
                String tokenOrDelim = tokenizer.nextToken();
                if (delim.contains(tokenOrDelim)) {
                    sb.append(tokenOrDelim);
                } else {
                    sb.append(StringUtils.capitalizeFirstLetter(tokenOrDelim));
                }
            }
            testSuiteName = sb.toString();
        }
        return testSuiteName;
    }

    private boolean shouldSkipTests() {
        return skipTests || skipITs || skipATs || skip;
    }

    private String[] generateRunArguments() {
        Arguments generatedArguments = new Arguments();

        generatedArguments.addFileToArguments(outputDirectory, "-d");
        generatedArguments.addFileToArguments(output, "-o");
        generatedArguments.addFileToArguments(log, "-l");
        generatedArguments.addFileToArguments(report, "-r");
        generatedArguments.addFileToArguments(debugFile, "-b");
        generatedArguments.addFileToArguments(argumentFile, "-A");

        generatedArguments.addNonEmptyStringToArguments(console, "--console");
        generatedArguments.addNonEmptyStringToArguments(name, "-N");
        generatedArguments.addNonEmptyStringToArguments(document, "-D");
        generatedArguments.addNonEmptyStringToArguments(runMode, "--runmode");
        generatedArguments.addFlagToArguments(rpa, "--rpa");
        generatedArguments.addFlagToArguments(dryrun, "--dryrun");
        generatedArguments.addFlagToArguments(exitOnFailure, "--exitonfailure");
        generatedArguments.addFlagToArguments(skipTeardownOnExit, "--skipteardownonexit");
        generatedArguments.addNonEmptyStringToArguments(randomize, "--randomize");
        generatedArguments.addNonEmptyStringToArguments(splitOutputs, "--splitoutputs");
        generatedArguments.addNonEmptyStringToArguments(logTitle, "--logtitle");
        generatedArguments.addNonEmptyStringToArguments(reportTitle, "--reporttitle");
        generatedArguments.addNonEmptyStringToArguments(reportBackground, "--reportbackground");
        generatedArguments.addNonEmptyStringToArguments(summaryTitle, "--summarytitle");
        generatedArguments.addNonEmptyStringToArguments(logLevel, "-L");
        generatedArguments.addNonEmptyStringToArguments(suiteStatLevel, "--suitestatlevel");
        generatedArguments.addNonEmptyStringToArguments(consoleWidth, "--consolewidth");
        generatedArguments.addNonEmptyStringToArguments(consoleColors, "--consolecolors");
        generatedArguments.addNonEmptyStringToArguments(listener, "--listener");

        generatedArguments.addFlagToArguments(runEmptySuite, "--runemptysuite");
        generatedArguments.addFlagToArguments(noStatusReturnCode, "--nostatusrc");
        generatedArguments.addFlagToArguments(timestampOutputs, "-T");
        generatedArguments.addFlagToArguments(warnOnSkippedFiles, "--warnonskippedfiles");

        generatedArguments.addListToArguments(metadata, "-M");
        generatedArguments.addListToArguments(tags, "-G");
        generatedArguments.addListToArguments(removeKeywords, "--removekeywords");
        generatedArguments.addListToArguments(flattenKeywords, "--flattenkeywords");
        if (tests_cli!=null)
            generatedArguments.addListToArguments(tests_cli, "-t");
        else
            generatedArguments.addListToArguments(tests, "-t");
        if (tasks_cli != null)
            generatedArguments.addListToArguments(tasks_cli, "--task");
        else
            generatedArguments.addListToArguments(tasks, "--task");
        if (suites_cli!=null)
            generatedArguments.addListToArguments(suites_cli, "-s");
        else
            generatedArguments.addListToArguments(suites, "-s");
        if (includes_cli!=null)
            generatedArguments.addListToArguments(includes_cli, "-i");
        else
            generatedArguments.addListToArguments(includes, "-i");
        if (excludes_cli!=null)
            generatedArguments.addListToArguments(excludes_cli, "-e");
        else
            generatedArguments.addListToArguments(excludes, "-e");
        generatedArguments.addListToArguments(criticalTags, "-c");
        generatedArguments.addListToArguments(nonCriticalTags, "-n");
        generatedArguments.addListToArguments(variables, "-v");
        if (variables_cli!=null)
            generatedArguments.addListToArguments(variables_cli, "-v");
        generatedArguments.addListToArguments(variableFiles, "-V");
        generatedArguments.addListToArguments(tagStatIncludes, "--tagstatinclude");
        generatedArguments.addListToArguments(tagStatExcludes, "--tagstatexclude");
        generatedArguments.addListToArguments(combinedTagStats, "--tagstatcombine");
        generatedArguments.addListToArguments(tagDocs, "--tagdoc");
        generatedArguments.addListToArguments(tagStatLinks, "--tagstatlink");
        generatedArguments.addListToArguments(listeners, "--listener");

        if (extraPathDirectories == null) {
            generatedArguments.addFileToArguments(defaultExtraPath, "-P");
        } else {
            generatedArguments.addFileListToArguments(Arrays.asList(extraPathDirectories), "-P");
        }

        if (xunitFile == null) {
            String testCasesFolderName = testCasesDirectory.getName();
            xunitFile = new File("TEST-" + testCasesFolderName.replace(' ', '_') + ".xml");
        }
        generatedArguments.addFileToArguments(xunitFile, "-x");
        generatedArguments.addFlagToArguments(true, "--xunitskipnoncritical");
        if (rerunFailed)
            generatedArguments.addFileToArguments(output, "--rerunfailed");
        generatedArguments.addFileToArguments(output, "-o");
        generatedArguments.add(testCasesDirectory.getPath());

        return generatedArguments.toArray();
    }

    /**
     * The directory where the test cases are located.
     *
     * @parameter default-value="${project.basedir}/src/test/robotframework/acceptance" property="testCasesDirectory"
     */
    private File testCasesDirectory;

    /**
     * Sets the name of the top-level tests suites.
     *
     * @parameter
     */
    private String name;

    /**
     * Sets the documentation of the top-level tests suites.
     *
     * @parameter
     */
    private String document;

    /**
     * Sets free metadata for the top level tests suites.
     *
     * @parameter
     */
    private List<String> metadata;

    /**
     * Sets the tags(s) to all executed tests cases.
     *
     * @parameter
     */
    private List<String> tags;

    /**
     * Selects the tasks cases by name.
     *
     * @parameter
     */
    private List<String> tasks;

    /**
     * Selects the tests cases by name.
     *
     * @parameter
     */
    private List<String> tests;

    /**
     * Selects the tests suites by name.
     *
     * @parameter
     */
    private List<String> suites;

    /**
     * Selects the tests cases by tags.
     *
     * @parameter
     */
    private List<String> includes;

    /**
     * Selects the tests cases by tags.
     *
     * @parameter
     */
    private List<String> excludes;

    /**
     * Selects the tests cases by name. Given as a comma separated list.
     * This setting overrides the value for tests configuration in pom.xml.
     *
     * <p>Example:<pre>
     * mvn -Dtests=foo,bar verify
     * </pre>
     * (This setting is needed to support overriding the configuration value from command prompt on maven 2.)
     * </p>
     * @parameter property="tests"
     */
    private String tests_cli;

    /**
     * Selects the tasks by name. Given as a comma separated list.
     * This setting overrides the value for tasks configuration in pom.xml.
     *
     * <p>Example:<pre>
     * mvn -Dtasks=foo,bar verify
     * </pre>
     * (This setting is needed to support overriding the configuration value from command prompt on maven 2.)
     * </p>
     * @parameter property="tasks"
     */
    private String tasks_cli;

    /**
     * Selects the tests suites by name. Given as a comma separated list.
     * This setting overrides the value for suites configuration in pom.xml.
     *
     * <p>Example:<pre>
     * mvn -Dsuites=foo,bar verify
     * </pre>
     * (This setting is needed to support overriding the configuration value from command prompt on maven 2.)
     * </p>
     * @parameter property="suites"
     */
    private String suites_cli;

    /**
     * Selects the tests cases by tags. Given as a comma separated list.
     * This setting overrides the value for includes configuration in pom.xml.
     *
     * <p>Example:<pre>
     * mvn -Dincludes=foo,bar verify
     * </pre>
     * (This setting is needed to support overriding the configuration value from command prompt on maven 2.)
     * </p>
     * @parameter property="includes"
     */
    private String includes_cli;

    /**
     * Selects the tests cases by tags. Given as a comma separated list.
     * This setting overrides the value for excludes configuration in pom.xml.
     *
     * <p>Example:<pre>
     * mvn -Dexcludes=foo,bar verify
     * </pre>
     * (This setting is needed to support overriding the configuration value from command prompt on maven 2.)
     * </p>
     * @parameter property="excludes"
     */
    private String excludes_cli;

    /**
     * Tests that have the given tags are considered critical.
     *
     * @parameter
     */
    private List<String> criticalTags;

    /**
     * Tests that have the given tags are not critical.
     *
     * @parameter
     */
    private List<String> nonCriticalTags;

    /**
     * Remove keywords and their messages altogether.
     * Instructions at http://robotframework.org/robotframework/latest/RobotFrameworkUserGuide.html#removing-keywords.
     *
     * <ul>
     * <li>'ALL' - Remove data from all keywords unconditionally.</li>
     * <li>'PASSED' -Remove keyword data from passed test cases. In most
     * cases, log files created using this option contain enough information
     * to investigate possible failures.</li>
     * <li>'FOR' - Remove all passed iterations from for loops except the last one.</li>
     * <li>'WUKS' - Remove all failing keywords inside BuiltIn keyword
     * 'Wait Until Keyword Succeeds' except the last one.</li>
     * <li>'NAME:{@literal <}pattern{@literal >}' - Remove data from all keywords matching the given pattern regardless the keyword status.</li>
     * <li>'TAG:{@literal <}pattern{@literal >}' - Remove data from keywords with tags that match the given pattern.</li>
     * </ul>
     *
     * The {@literal <}pattern{@literal >} is case, space, and underscore insensitive, and it supports simple patterns with * and ? as wildcards.
     *
     * @parameter
     */
    private List<String> removeKeywords;

    /**
     * Flatten keywords and their messages altogether.
     * Instructions at http://robotframework.org/robotframework/latest/RobotFrameworkUserGuide.html#flattening-keywords.
     *
     * <ul>
     * <li>'FOR' - Flatten for loops fully.</li>
     * <li>'FORITEM' - Flatten individual for loop iterations.</li>
     * <li>'NAME:{@literal <}pattern{@literal >}' - Flatten keywords matching the given pattern.</li>
     * <li>'TAG:{@literal <}pattern{@literal >}' - Flatten keywords with tags matching the given pattern.</li>
     * </ul>
     *
     * The {@literal <}pattern{@literal >} is case, space, and underscore insensitive, and it supports simple patterns with * and ? as wildcards.
     *
     * @parameter
     */
    private List<String> flattenKeywords;

    /**
     * Sets the execution mode for this tests run. Note that this setting has
     * been deprecated in Robot Framework 2.8. Use separate dryryn,
     * skipTeardownOnExit, exitOnFailure, and randomize settings instead.
     *
     * @parameter
     */
    private String runMode;

    /**
     * Sets dryrun mode on use. In the dry run mode tests are run without
     * executing keywords originating from test libraries. Useful for
     * validating test data syntax.
     *
     * @parameter default-value="false"
     */
    private boolean dryrun;

    /**
     * Sets whether the teardowns are skipped if the test
     * execution is prematurely stopped.
     *
     * @parameter default-value="false"
     */
    private boolean skipTeardownOnExit;

    /**
     * Sets robot to stop execution immediately if a critical test fails.
     *
     * @parameter default-value="false"
     */
    private boolean exitOnFailure;

    /**
     * Sets the test execution order to be randomized. Valid values are all,
     * suite, and test
     *
     * @parameter
     */
    private String randomize;

    /**
     * Sets individual variables. Use the format "name:value"
     *
     * @parameter
     */
    private List<String> variables;

    /**
     * Sets individual variables. Use the format "name:value" and separate entries with comma.
     * These are added into variables defined in the pom (variables with same name are overridden).
     *
     * (This setting is needed to support overriding the configuration value from command prompt on maven 2.)
     *
     * @parameter property="variables"
     */
    private String variables_cli;

    /**
     * Sets variables using variables files. Use the format "path:args"
     *
     * @parameter
     */
    private List<String> variableFiles;

    /**
     * Configures where generated reports are to be placed.
     *
     * @parameter default-value="${project.build.directory}/robotframework-reports"
     */
    private File outputDirectory;

    /**
     * Sets the path to the generated output file.
     *
     * @parameter
     */
    private File output;

    /**
     * Sets the path to the generated log file.
     *
     * @parameter
     */
    private File log;

    /**
     * Sets the path to the generated report file.
     *
     * @parameter
     */
    private File report;

    /**
     * Sets the path to the generated XUnit compatible result file, relative to outputDirectory. The
     * file is in xml format. By default, the file name is derived from the testCasesDirectory
     * parameter, replacing blanks in the directory name by underscores.
     *
     * @parameter
     */
    private File xunitFile;

    /**
     * A debug file that is written during execution.
     *
     * @parameter
     */
    private File debugFile;

    /**
     * Adds a timestamp to all output files.
     *
     * @parameter
     */
    private boolean timestampOutputs;

    /**
     * Splits output and log files.
     *
     * @parameter
     */
    private String splitOutputs;

    /**
     * Sets a title for the generated tests log.
     *
     * @parameter
     */
    private String logTitle;

    /**
     * Sets a title for the generated tests report.
     *
     * @parameter
     */
    private String reportTitle;

    /**
     * Sets a title for the generated summary report.
     *
     * @parameter
     */
    private String summaryTitle;

    /**
     * Sets background colors for the generated report and summary.
     *
     * @parameter
     */
    private String reportBackground;

    /**
     * Sets the threshold level for logging.
     *
     * @parameter
     */
    private String logLevel;

    /**
     * Defines how many levels to show in the Statistics by Suite table in outputs.
     *
     * @parameter
     */
    private String suiteStatLevel;

    /**
     * Includes only these tags in the Statistics by Tag and Test Details by Tag tables in outputs.
     *
     * @parameter
     */
    private List<String> tagStatIncludes;

    /**
     * Excludes these tags from the Statistics by Tag and Test Details by Tag tables in outputs.
     *
     * @parameter
     */
    private List<String> tagStatExcludes;

    /**
     * Creates combined statistics based on tags. Use the format "tags:title"
     *
     * @parameter
     */
    private List<String> combinedTagStats;

    /**
     * Adds documentation to the specified tags.
     *
     * @parameter
     */
    private List<String> tagDocs;

    /**
     * Adds external links to the Statistics by Tag table in outputs. Use the format
     * "pattern:link:title"
     *
     * @parameter
     */
    private List<String> tagStatLinks;

    /**
     * Sets multiple listeners for monitoring tests execution. Use the format "ListenerWithArgs:arg1:arg2" or
     * simply "ListenerWithoutArgs"
     *
     * @parameter
     */
    private List<String> listeners;

    /**
     * Sets a single listener for monitoring tests execution, can also be set via commandline using
     * -Dlistener=MyListener.
     *
     * @parameter property="listener"
     */
    private String listener;

    /**
     * Show a warning when an invalid file is skipped.
     *
     * @parameter
     */
    private boolean warnOnSkippedFiles;

    /**
     * Width of the console output. Default is 78.
     *
     * @parameter
     */
    private String consoleWidth;

    /**
     * Using ANSI colors in console. Normally colors work in unixes but not in Windows. Default is
     * 'on'.
     * <ul>
     * <li>'auto' - Colors are enabled when outputs are written into the console, but not when they
     * are redirected into a file or elsewhere. This is the default.</li>
     * <li>'on' - Colors are used also when outputs are redirected. Does not work on Windows.</li>
     * <li>'ansi' - Same as on but uses ANSI colors also on Windows. Useful, for example, when
     * redirecting output to a program that understands ANSI colors.</li>
     * <li>'off' - Colors are disabled</li>
     * </ul>
     *
     */
    private String consoleColors;

    /**
     * The overall console output type. It supports the following case-insensitive values.
     * Default is 'verbose'.
     * <ul>
     * <li>'verbose' - Every test suite and test case is reported individually</li>
     * <li>'dotted' - Only show . for passed test, f for failed non-critical tests, F for
     * failed critical tests, and x for tests which are skipped because test execution exit.
     * Failed critical tests are listed separately after execution. This output type makes
     * it easy to see are there any failures during execution even if there would be a lot
     * of tests.</li>
     * <li>'quiet' - No output except for errors and warnings.</li>
     * <li>'none' - No output whatsoever.</li>
     * </ul>
     *
     * Note that CLI shortcuts --dotted and --quiet are not supported.
     *
     * @parameter default-value="verbose"
     */
    private String console;

    /**
     * Additional locations (directories, ZIPs, JARs) where to search test libraries from when they
     * are imported. Maps to Jybot's --pythonpath option. Otherwise if no locations are declared,
     * the default location is ${project.basedir}/src/test/resources/robotframework/libraries.
     *
     * @parameter
     */
    private File[] extraPathDirectories;

    /**
     * The default location where extra packages will be searched. Effective if extraPath attribute
     * is not used. Cannot be overridden.
     *
     * @parameter default-value="${project.basedir}/src/test/resources/robotframework/libraries"
     * @required
     * @readonly
     */
    private File defaultExtraPath;

    /**
     * A text file to read more arguments from.
     *
     * @parameter property="argumentFile"
     */
    private File argumentFile;

    /**
     * Skip tests. Bound to -DskipTests. This allows to skip acceptance tests together with all
     * other tests.
     *
     * @parameter property="skipTests"
     */
    private boolean skipTests;

    /**
     * Skip acceptance tests executed by this plugin. Bound to -DskipATs. This allows to run tests
     * and integration tests, but no acceptance tests.
     *
     * @parameter property="skipATs"
     */
    private boolean skipATs;

    /**
     * Skip acceptance tests executed by this plugin together with other integration tests, e.g.
     * tests run by the maven-failsafe-plugin. Bound to -DskipITs
     *
     * @parameter property="skipITs"
     */
    private boolean skipITs;

    /**
     * Skip tests, bound to -Dmaven.test.skip, which suppresses test compilation as well.
     *
     * @parameter default-value="false" property="maven.test.skip"
     */
    private boolean skip;

    /**
     * Executes tests also if the top level test suite is empty. Useful e.g. with
     * --include/--exclude when it is not an error that no test matches the condition.
     *
     * @parameter default-value="false"
     */
    private boolean runEmptySuite;

    /**
     * If true, sets the return code to zero regardless of failures in test cases. Error codes are
     * returned normally.
     *
     * @parameter default-value="false"
     */
    private boolean noStatusReturnCode;

    /**
     * Turn on generic automation mode.
     *
     * @parameter default-value="false"
     */
    private boolean rpa;

    /**
     * Run only failed test cases. Note that output.xml has to be available.
     * By default clean goal would remove output.xml making it impossible to
     * run failed cases.
     *
     * @parameter property="rerunfailed"
     */
    private boolean rerunFailed;

}

class StreamReader extends Thread {

    BufferedReader input;

    StreamReader(InputStream input) {
        this.input = new BufferedReader(new InputStreamReader(input));
    }

    public void run() {
        try {
            String line=null;
            while ((line = input.readLine()) != null)
                System.out.println(line);
        } catch (IOException ioe) {
            ioe.printStackTrace(); }
    }
}
