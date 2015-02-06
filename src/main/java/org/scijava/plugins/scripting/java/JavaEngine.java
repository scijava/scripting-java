/*
 * #%L
 * JSR-223-compliant Java scripting language plugin.
 * %%
 * Copyright (C) 2008 - 2015 Board of Regents of the University of
 * Wisconsin-Madison, Broad Institute of MIT and Harvard, and Max Planck
 * Institute of Molecular Cell Biology and Genetics.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package org.scijava.plugins.scripting.java;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes.Name;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.scijava.command.CommandService;
import org.scijava.minimaven.BuildEnvironment;
import org.scijava.minimaven.Coordinate;
import org.scijava.minimaven.MavenProject;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.PluginService;
import org.scijava.script.AbstractScriptEngine;
import org.scijava.util.FileUtils;
import org.scijava.util.LineOutputStream;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/**
 * A pseudo-{@link ScriptEngine} compiling and executing Java classes.
 * <p>
 * Thanks to <a href="https://github.com/scijava/minimaven">MiniMaven</a>, this
 * script engine can handle individual Java classes as well as trivial Maven
 * projects (triggered when the proviede script path suggests that the file is
 * part of a Maven project).
 * </p>
 * 
 * @author Johannes Schindelin
 * @author Jonathan Hale
 */
public class JavaEngine extends AbstractScriptEngine {

	private final static String DEFAULT_GROUP_ID = "org.scijava.scripting.java";
	private final static String DEFAULT_VERSION = "1.0.0-SNAPSHOT";

	/**
	 * The key to specify how to indent the XML written out by Xalan.
	 */
	private final static String XALAN_INDENT_AMOUNT =
		"{http://xml.apache.org/xslt}indent-amount";

	{
		engineScopeBindings = new JavaEngineBindings();
	}

	@Parameter
	private PluginService pluginService;

	@Parameter
	private CommandService commandService;

	@Parameter
	private JavaService javaService;

	/**
	 * Compiles and runs the specified {@code .java} class. If a filename is set
	 * in the engine scope bindings via the {@link ScriptEngine#FILENAME} key,
	 * this method compiles that file and runs the resulting main class instead.
	 * <p>
	 * The currently active {@link JavaService} is responsible for running the
	 * class.
	 * </p>
	 * 
	 * @param script the source code for a Java class
	 * @return null
	 */
	@Override
	public Object eval(String script) throws ScriptException {
		final Writer writer = getContext().getErrorWriter();
		try {
			final Class<?> clazz = compile(script);
			javaService.run(clazz);
		}
		catch (Exception e) {
			if (writer != null) {
				final PrintWriter err = new PrintWriter(writer);
				e.printStackTrace(err);
				err.flush();
			}
			else {
				if (e instanceof ScriptException) throw (ScriptException) e;
				throw new ScriptException(e);
			}
		}
		return null;
	}

	/**
	 * Compiles and runs the specified {@code .java} class. If a filename is set
	 * in the engine scope bindings via the {@link ScriptEngine#FILENAME} key,
	 * this method compiles that file and runs the resulting main class instead.
	 * <p>
	 * The currently active {@link JavaService} is responsible for running the
	 * class.
	 * </p>
	 * 
	 * @param reader the reader producing the source code for a Java class
	 * @return null
	 */
	@Override
	public Object eval(Reader reader) throws ScriptException {
		String script;
		try {
			script = getReaderContentsAsString(reader);
		}
		catch (IOException e) {
			throw new ScriptException(e);
		}
		return eval(script);
	}

	/**
	 * Compiles and runs the specified {@code .java} class. If a filename is set
	 * in the engine scope bindings via the {@link ScriptEngine#FILENAME} key,
	 * this method compiles that file and returns its resulting main class
	 * instead.
	 * 
	 * @param script the source code for a Java class
	 * @return the compiled Java class as {@link Class}.
	 */
	public Class<?> compile(String script) throws ScriptException {
		// get filename from engine scope bindings
		final String path = (String) get(FILENAME);
		File file = path == null ? null : new File(path);

		final Writer writer = getContext().getErrorWriter();
		final Builder builder = new Builder();
		try {
			if (file != null && file.exists()) {
				// if the filename set in engine scope bindings is valid,
				// ignore the given script and use that file instead.
				builder.initialize(file, writer);
			}
			else {
				// script may be null, but then we cannot create a StringReader for it,
				// therefore null is passed if script is null.
				final Reader reader =
					(script == null) ? null : new StringReader(script);
				builder.initialize(reader, writer);
			}
			final MavenProject project = builder.project;
			String mainClass = builder.mainClass;

			project.build(true);
			if (mainClass == null) {
				mainClass = project.getMainClass();
				if (mainClass == null) {
					throw new ScriptException("No main class found for file " + file);
				}
			}

			// make class loader
			String[] paths = project.getClassPath(false).split(File.pathSeparator);
			URL[] urls = new URL[paths.length];
			for (int i = 0; i < urls.length; i++)
				urls[i] =
					new URL("file:" + paths[i] + (paths[i].endsWith(".jar") ? "" : "/"));

			final URLClassLoader classLoader =  new URLClassLoader(urls, Thread.currentThread()
					.getContextClassLoader());

			// load main class
			return classLoader.loadClass(mainClass);
		}
		catch (Exception e) {
			if (writer != null) {
				final PrintWriter err = new PrintWriter(writer);
				e.printStackTrace(err);
				err.flush();
			}
			else {
				if (e instanceof ScriptException) throw (ScriptException) e;
				throw new ScriptException(e);
			}
		}
		finally {
			builder.cleanup();
		}
		return null;
	}

	/**
	 * Compiles and runs the specified {@code .java} class. If a filename is set
	 * in the engine scope bindings via the {@link ScriptEngine#FILENAME} key,
	 * this method compiles that file and returns its resulting main class
	 * instead.
	 *
	 * @param reader the reader producing the source code for a Java class
	 * @return the compiled Java class as {@link Class}.
	 */
	public Class<?> compile(Reader reader) throws ScriptException {
		String script;
		try {
			script = getReaderContentsAsString(reader);
		}
		catch (IOException e) {
			throw new ScriptException(e);
		}
		return compile(script);
	}

	/**
	 * Compiles the specified {@code .java} file. Errors are written to the
	 * context error writer.
	 * 
	 * @param file the source code
	 * @see #compile(File, Writer)
	 * @see #compile(Reader)
	 * @see #compile(String)
	 */
	public void compile(final File file) {
		compile(file, null);
	}

	/**
	 * Compiles the specified {@code .java} file. Errors are written to the
	 * specified errorWriter or if it is null, to the context error writer.
	 * 
	 * @param file the source code
	 * @param errorWriter where to write the errors or null to use context
	 *          errorWriter
	 * @see #compile(File)
	 * @see #compile(Reader)
	 * @see #compile(String)
	 */
	public void compile(final File file, final Writer errorWriter) {
		final Writer writer =
			(errorWriter == null) ? getContext().getErrorWriter() : errorWriter;
		final Builder builder = new Builder();
		try {
			builder.initialize(file, writer);
			builder.project.build();
		}
		catch (Throwable t) {
			printOrThrow(t, errorWriter);
		}
		finally {
			builder.cleanup();
		}
	}

	/**
	 * Packages the build product into a {@code .jar} file.
	 * 
	 * @param file a {@code .java} or {@code pom.xml} file
	 * @param includeSources whether to include the sources or not
	 * @param output the {@code .jar} file to write to
	 * @param errorWriter the destination for error messages
	 */
	public void makeJar(final File file, final boolean includeSources,
		final File output, final Writer errorWriter)
	{
		final Builder builder = new Builder();
		try {
			builder.initialize(file, errorWriter);
			builder.project.build(true, true, includeSources);
			final File target = builder.project.getTarget();
			if (output != null && !target.equals(output)) {
				BuildEnvironment.copyFile(target, output);
			}
		}
		catch (Throwable t) {
			printOrThrow(t, errorWriter);
		}
		finally {
			builder.cleanup();
		}
	}

	/**
	 * Reports an exception.
	 * <p>
	 * If a writer for errors is specified (e.g. when being called from the script
	 * editor), we should just print the error and return. Otherwise, we'll throw
	 * the exception back at the caller.
	 * </p>
	 * 
	 * @param t the exception
	 * @param errorWriter the error writer, or null
	 */
	private void printOrThrow(Throwable t, Writer errorWriter) {
		RuntimeException e =
			t instanceof RuntimeException ? (RuntimeException) t
				: new RuntimeException(t);
		if (errorWriter == null) {
			throw e;
		}
		final PrintWriter err = new PrintWriter(errorWriter);
		e.printStackTrace(err);
		err.flush();
	}

	/**
	 * A wrapper around a (possibly only temporary) project.
	 * 
	 * @author Johannes Schindelin
	 * @author Jonathan Hale
	 */
	private class Builder {

		private PrintStream err;
		private File temporaryDirectory;
		private String mainClass;
		private MavenProject project;

		/**
		 * Constructs a wrapper around a possibly project for a source or maven
		 * project file.
		 * <p>
		 * This method is intended to be called only once.
		 * </p>
		 * 
		 * @param file the {@code .java} file to build (or null, if {@code reader}
		 *          is set).
		 * @param errorWriter where to write the error output.
		 * @throws ScriptException
		 * @throws IOException
		 * @throws ParserConfigurationException
		 * @throws SAXException
		 * @throws TransformerConfigurationException
		 * @throws TransformerException
		 * @throws TransformerFactoryConfigurationError
		 */
		private void initialize(final File file, final Writer errorWriter)
			throws ScriptException, IOException, ParserConfigurationException,
			SAXException, TransformerConfigurationException, TransformerException,
			TransformerFactoryConfigurationError
		{
			err = createErrorPrintStream(errorWriter);

			BuildEnvironment env = createBuildEnvironment();

			// will throw IOException if file does not exist.
			temporaryDirectory = null;
			if (file.getName().equals("pom.xml")) {
				project = env.parse(file, null);
			}
			else {
				mainClass = getFullClassName(file);
				project = getMavenProject(env, file, mainClass);
			}
		}

		/**
		 * Constructs a wrapper around a possibly temporary project for source code
		 * generated by a Reader.
		 * <p>
		 * This method is intended to be called only once.
		 * </p>
		 * 
		 * @param reader provides the Java source if {@code file} is {@code null}
		 * @param errorWriter where to write the error output.
		 * @throws ScriptException
		 * @throws IOException
		 * @throws ParserConfigurationException
		 * @throws SAXException
		 * @throws TransformerConfigurationException
		 * @throws TransformerException
		 * @throws TransformerFactoryConfigurationError
		 */
		private void initialize(final Reader reader, final Writer errorWriter)
			throws ScriptException, IOException, ParserConfigurationException,
			SAXException, TransformerConfigurationException, TransformerException,
			TransformerFactoryConfigurationError
		{
			err = createErrorPrintStream(errorWriter);

			BuildEnvironment env = createBuildEnvironment();

			try {
				project = writeTemporaryProject(env, reader);
				temporaryDirectory = project.getDirectory();
				mainClass = project.getMainClass();
			}
			catch (Exception e) {
				throw new ScriptException(e);
			}
		}

		/**
		 * Create a {@link PrintStream} from an error {@link Writer}.
		 * 
		 * @param errorWriter the {@link Writer} to write errors to.
		 * @return a {@link PrintStream} which writes to errorWriter.
		 */
		private PrintStream createErrorPrintStream(final Writer errorWriter) {
			if (errorWriter == null) {
				return null;
			}

			// create a PrintStream which redirects output to errorWriter
			return new PrintStream(new LineOutputStream() {

				@Override
				public void println(final String line) throws IOException {
					errorWriter.append(line).append('\n');
				}

			});
		}

		/**
		 * Create a {@link BuildEnvironment} for current engine scope bindings
		 * context.
		 * 
		 * @return the created {@link BuildEnvironment}.
		 */
		private BuildEnvironment createBuildEnvironment() {
			boolean verbose = "true".equals(get("verbose")) || log().isInfo();
			boolean debug = "true".equals(get("debug")) || log().isDebug();
			return new BuildEnvironment(err, true, verbose, debug);
		}

		/**
		 * Cleans up the project, if it was only temporary.
		 */
		private void cleanup() {
			if (err != null) err.close();
			if (err != null) err.close();
			if (temporaryDirectory != null &&
				!FileUtils.deleteRecursively(temporaryDirectory))
			{
				temporaryDirectory.deleteOnExit();
			}
		}
	}

	/**
	 * Returns a Maven POM associated with a {@code .java} file.
	 * <p>
	 * If the file is not part of a valid Maven project, one will be generated.
	 * </p>
	 * 
	 * @param env the {@link BuildEnvironment}
	 * @param file the {@code .java} file
	 * @param mainClass the name of the class to execute
	 * @return the Maven POM
	 * @throws IOException
	 * @throws ParserConfigurationException
	 * @throws SAXException
	 * @throws ScriptException
	 * @throws TransformerConfigurationException
	 * @throws TransformerException
	 * @throws TransformerFactoryConfigurationError
	 */
	private MavenProject getMavenProject(final BuildEnvironment env,
		final File file, final String mainClass) throws IOException,
		ParserConfigurationException, SAXException, ScriptException,
		TransformerConfigurationException, TransformerException,
		TransformerFactoryConfigurationError
	{
		String path = file.getAbsolutePath();
		if (!path.replace(File.separatorChar, '.').endsWith(
			"." + mainClass + ".java"))
		{
			throw new ScriptException("Class " + mainClass +
				" in invalid directory: " + path);
		}
		path = path.substring(0, path.length() - mainClass.length() - 5);
		if (path.replace(File.separatorChar, '/').endsWith("/src/main/java/")) {
			path = path.substring(0, path.length() - "src/main/java/".length());
			final File pom = new File(path, "pom.xml");
			if (pom.exists()) return env.parse(pom, null);
		}
		return writeTemporaryProject(env, new FileReader(file));
	}

	/**
	 * Determines the class name of a Java class given its source code.
	 * 
	 * @param file the source code
	 * @return the class name including the package
	 * @throws IOException
	 */
	private static String getFullClassName(final File file) throws IOException {
		String name = file.getName();
		if (!name.endsWith(".java")) {
			throw new UnsupportedOperationException();
		}
		name = name.substring(0, name.length() - 5);

		String packageName = "";
		final Pattern packagePattern =
			Pattern.compile("package ([a-zA-Z0-9_.]*).*");
		final Pattern classPattern =
			Pattern.compile(".*public class ([a-zA-Z0-9_]*).*");
		final BufferedReader reader = new BufferedReader(new FileReader(file));
		for (;;) {
			String line = reader.readLine();
			if (line == null) break;
			line = line.trim();
			outerLoop:
			while (line.startsWith("/*")) {
				int end = line.indexOf("*/", 2);
				while (end < 0) {
					line = reader.readLine();
					if (line == null) break outerLoop;
					end = line.indexOf("*/");
				}
				line = line.substring(end + 2).trim();
			}
			if (line == null || line.equals("") || line.startsWith("//")) continue;
			final Matcher packageMatcher = packagePattern.matcher(line);
			if (packageMatcher.matches()) {
				packageName = packageMatcher.group(1) + ".";
			}
			final Matcher classMatcher = classPattern.matcher(line);
			if (classMatcher.matches()) {
				name = classMatcher.group(1);
				break;
			}
		}
		reader.close();
		return packageName + name; // the 'package' statement must be the first in
																// the file
	}

	/**
	 * Makes a temporary Maven project for a virtual {@code .java} file.
	 * 
	 * @param env the {@link BuildEnvironment} to store the generated Maven POM
	 * @param reader the virtual {@code .java} file
	 * @return the generated Maven POM
	 * @throws IOException
	 * @throws ParserConfigurationException
	 * @throws SAXException
	 * @throws TransformerConfigurationException
	 * @throws TransformerException
	 * @throws TransformerFactoryConfigurationError
	 */
	private static MavenProject writeTemporaryProject(final BuildEnvironment env,
		final Reader reader) throws IOException, ParserConfigurationException,
		SAXException, TransformerConfigurationException, TransformerException,
		TransformerFactoryConfigurationError
	{
		final File directory = FileUtils.createTemporaryDirectory("java", "");
		final File file = new File(directory, ".java");

		final BufferedReader in = new BufferedReader(reader);
		final Writer out = new FileWriter(file);
		for (;;) {
			final String line = in.readLine();
			if (line == null) break;
			out.write(line);
			out.write('\n');
		}
		in.close();
		out.close();

		final String mainClass = getFullClassName(file);
		final File result =
			new File(directory, "src/main/java/" + mainClass.replace('.', '/') +
				".java");
		if (!result.getParentFile().mkdirs()) {
			throw new IOException("Could not make directory for " + result);
		}
		if (!file.renameTo(result)) {
			throw new IOException("Could not move " + file +
				" into the correct location");
		}

		// write POM
		final String artifactId =
			mainClass.substring(mainClass.lastIndexOf('.') + 1);
		return fakePOM(env, directory, artifactId, mainClass, true);
	}

	/**
	 * Fakes a sensible, valid {@code artifactId}.
	 * <p>
	 * Given a name for a project or {@code .java} file, this function generated a
	 * proper {@code artifactId} for use in faked Maven POMs.
	 * </p>
	 * 
	 * @param env the associated {@link BuildEnvironment} (to avoid duplicate
	 *          {@code artifactId}s)
	 * @param name the project name
	 * @return the generated {@code artifactId}
	 */
	private static String fakeArtifactId(final BuildEnvironment env,
		final String name)
	{
		int dot = name.indexOf('.');
		final String prefix =
			dot < 0 ? name : dot == 0 ? "dependency" : name.substring(0, dot);
		if (!env.containsProject(DEFAULT_GROUP_ID, prefix)) {
			return prefix;
		}
		for (int i = 1;; i++) {
			final String artifactId = prefix + "-" + i;
			if (!env.containsProject(DEFAULT_GROUP_ID, artifactId)) {
				return artifactId;
			}
		}
	}

	/**
	 * Fakes a single Maven POM for a given dependency.
	 * <p>
	 * When discovering possible dependencies on the class path, we do not
	 * necessarily deal with proper Maven-generated artifacts. To be able to use
	 * them for single {@code .java} "scripts", we simply fake Maven POMs for
	 * those files.
	 * </p>
	 * 
	 * @param env the {@link BuildEnvironment} to house the faked POM
	 * @param directory the directory associated with the Maven project
	 * @param artifactId the {@code artifactId} of the dependency
	 * @param mainClass the main class, if any
	 * @param writePOM whether to write the Maven POM as {@code pom.xml} into the
	 *          specified directory
	 * @return the faked POM
	 * @throws IOException
	 * @throws ParserConfigurationException
	 * @throws SAXException
	 * @throws TransformerConfigurationException
	 * @throws TransformerException
	 * @throws TransformerFactoryConfigurationError
	 */
	private static MavenProject fakePOM(final BuildEnvironment env,
		final File directory, final String artifactId, final String mainClass,
		boolean writePOM) throws IOException, ParserConfigurationException,
		SAXException, TransformerConfigurationException, TransformerException,
		TransformerFactoryConfigurationError
	{
		final Document pom =
			DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
		final Element project = pom.createElement("project");
		pom.appendChild(project);
		project.setAttribute("xmlns", "http://maven.apache.org/POM/4.0.0");
		project.setAttribute("xmlns:xsi",
			"http://www.w3.org/2001/XMLSchema-instance");
		project.setAttribute("xsi:schemaLocation",
			"http://maven.apache.org/POM/4.0.0 "
				+ "http://maven.apache.org/xsd/maven-4.0.0.xsd");

		append(pom, project, "groupId", DEFAULT_GROUP_ID);
		append(pom, project, "artifactId", artifactId);
		append(pom, project, "version", DEFAULT_VERSION);

		final Element build = append(pom, project, "build", null);

		if (mainClass != null) {
			final Element plugins = append(pom, build, "plugins", null);
			final Element plugin = append(pom, plugins, "plugin", null);
			append(pom, plugin, "artifactId", "maven-jar-plugin");
			final Element configuration = append(pom, plugin, "configuration", null);
			final Element archive = append(pom, configuration, "archive", null);
			final Element manifest = append(pom, archive, "manifest", null);
			append(pom, manifest, "mainClass", mainClass);
		}

		Element dependencies = append(pom, project, "dependencies", null);
		for (Coordinate dependency : getAllDependencies(env)) {
			Element dep = append(pom, dependencies, "dependency", null);
			append(pom, dep, "groupId", dependency.getGroupId());
			append(pom, dep, "artifactId", dependency.getArtifactId());
			append(pom, dep, "version", dependency.getVersion());
		}
		final Transformer transformer =
			TransformerFactory.newInstance().newTransformer();
		transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
		transformer.setOutputProperty(OutputKeys.INDENT, "yes");
		transformer.setOutputProperty(XALAN_INDENT_AMOUNT, "4");
		if (directory.getPath().replace(File.separatorChar, '/').endsWith(
			"/src/main/java"))
		{
			final File projectRootDirectory =
				directory.getParentFile().getParentFile().getParentFile();
			final File pomFile = new File(projectRootDirectory, "pom.xml");
			if (!pomFile.exists()) {
				final FileWriter writer = new FileWriter(pomFile);
				transformer.transform(new DOMSource(pom), new StreamResult(writer));
				return env.parse(pomFile);
			}
		}
		if (writePOM) {
			transformer.transform(new DOMSource(pom), new StreamResult(new File(
				directory, "pom.xml")));
		}
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		transformer.transform(new DOMSource(pom), new StreamResult(out));
		return env.parse(new ByteArrayInputStream(out.toByteArray()), directory,
			null, null);
	}

	/**
	 * Writes out the specified XML element.
	 * 
	 * @param document the XML document
	 * @param parent the parent node
	 * @param tag the tag to append
	 * @param content the content of the tag to append
	 * @return the appended node
	 */
	private static Element append(final Document document, final Element parent,
		final String tag, final String content)
	{
		Element child = document.createElement(tag);
		if (content != null) child
			.appendChild(document.createCDATASection(content));
		parent.appendChild(child);
		return child;
	}

	/**
	 * Discovers all current class path elements and offers them as faked Maven
	 * POMs.
	 * <p>
	 * When constructing an in-memory Maven POM for a single {@code .java} file,
	 * we need to make sure that all class path elements are available to the
	 * compiler. Since we use MiniMaven to compile everything (in order to be
	 * consistent, and also to be able to generate Maven projects conveniently, to
	 * turn hacky projects into proper ones), we need to put all of that into the
	 * Maven context, i.e. fake Maven POMs for all the dependencies.
	 * </p>
	 * 
	 * @param env the {@link BuildEnvironment} in which the faked POMs are stored
	 * @return the list of dependencies, as {@link Coordinate}s
	 */
	private static List<Coordinate>
		getAllDependencies(final BuildEnvironment env)
	{
		final List<Coordinate> result = new ArrayList<Coordinate>();
		for (ClassLoader loader = Thread.currentThread().getContextClassLoader(); loader != null; loader =
			loader.getParent())
		{
			if (loader instanceof URLClassLoader) {
				for (final URL url : ((URLClassLoader) loader).getURLs()) {
					if (url.getProtocol().equals("file")) {
						final File file = new File(url.getPath());
						if (url.toString().matches(
							".*/target/surefire/surefirebooter[0-9]*\\.jar"))
						{
							getSurefireBooterURLs(file, url, env, result);
							continue;
						}
						result.add(fakeDependency(env, file));
					}
				}
			}
		}
		return result;
	}

	/**
	 * Fakes a Maven POM in memory for a specified dependency.
	 * <p>
	 * When compiling bare {@code .java} files, we need to fake a full-blown Maven
	 * project, including full-blown Maven dependencies for all of the files
	 * present on the current class path.
	 * </p>
	 * 
	 * @param env the {@link BuildEnvironment} for storing the faked Maven POM
	 * @param file the dependency
	 * @return the {@link Coordinate} specifying the dependency
	 */
	private static Coordinate fakeDependency(final BuildEnvironment env,
		final File file)
	{
		final String artifactId = fakeArtifactId(env, file.getName());
		Coordinate dependency =
			new Coordinate(DEFAULT_GROUP_ID, artifactId, "1.0.0");
		env.fakePOM(file, dependency);
		return dependency;
	}

	/**
	 * Figures out the class path given a {@code .jar} file generated by the
	 * {@code maven-surefire-plugin}.
	 * <p>
	 * A little-known feature of JAR files is that their manifest can specify
	 * additional class path elements in a {@code Class-Path} entry. The
	 * {@code maven-surefire-plugin} makes extensive use of that: the URLs of the
	 * of the active {@link URLClassLoader} will consist of only a single
	 * {@code .jar} file that is empty except for a manifest whose sole purpose is
	 * to specify the dependencies.
	 * </p>
	 * <p>
	 * This method can be used to discover those additional class path elements.
	 * </p>
	 * 
	 * @param file the {@code .jar} file generated by the
	 *          {@code maven-surefire-plugin}
	 * @param baseURL the {@link URL} of the {@code .jar} file, needed for class
	 *          path elements specified as relative paths
	 * @param env the {@link BuildEnvironment}, to store the Maven POMs faked for
	 *          the class path elements
	 * @param result the list of dependencies to which the discovered dependencies
	 *          are added
	 */
	private static void getSurefireBooterURLs(final File file, final URL baseURL,
		final BuildEnvironment env, final List<Coordinate> result)
	{
		try {
			final JarFile jar = new JarFile(file);
			Manifest manifest = jar.getManifest();
			if (manifest != null) {
				final String classPath =
					manifest.getMainAttributes().getValue(Name.CLASS_PATH);
				if (classPath != null) {
					for (final String element : classPath.split(" +"))
						try {
							final File dependency =
								new File(new URL(baseURL, element).getPath());
							result.add(fakeDependency(env, dependency));
						}
						catch (MalformedURLException e) {
							e.printStackTrace();
						}
				}
			}
		}
		catch (final IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Read complete contents of a Reader and return as String.
	 *
	 * @param reader {@link Reader} whose output should be returned as String.
	 * @return contents of reader as String.
	 */
	private static String getReaderContentsAsString(Reader reader)
		throws IOException
	{
		if (reader == null) {
			return null;
		}

		char[] buffer = new char[1024];
		StringBuilder builder = new StringBuilder();

		int read;
		while ((read = reader.read(buffer)) != -1) {
			builder.append(buffer, 0, read);
		}

		return builder.toString();
	}

}
