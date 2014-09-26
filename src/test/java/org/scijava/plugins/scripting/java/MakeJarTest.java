/*
 * #%L
 * JSR-223-compliant Java scripting language plugin.
 * %%
 * Copyright (C) 2008 - 2014 Board of Regents of the University of
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.junit.Test;
import org.scijava.test.TestUtils;
import org.scijava.util.FileUtils;
import org.scijava.util.IteratorPlus;

public class MakeJarTest {
	@Test
	public void testSingle() throws Exception {
		final StringWriter writer = new StringWriter();
		final JavaEngine engine = new JavaEngine();
		final File file = FileUtils.urlToFile(getClass().getResource("/Dummy.java"));
		final File tmpDir = TestUtils.createTemporaryDirectory("jar-test-");
		final File output = new File(tmpDir, "test.jar");
		engine.makeJar(file, false, output, writer);
		assertJarEntries(output, "META-INF/MANIFEST.MF",
				"META-INF/maven/org.scijava.scripting.java/Dummy/pom.xml", "Dummy.class");
		engine.makeJar(file, true, output, writer);
		assertJarEntries(output, "META-INF/MANIFEST.MF",
				"META-INF/maven/org.scijava.scripting.java/Dummy/pom.xml", "Dummy.class",
				"pom.xml", "src/main/java/Dummy.java");
	}

	private void assertJarEntries(File output, String... paths) throws IOException {
		final Set<String> set = new TreeSet<String>(Arrays.asList(paths));
		final JarFile jar = new JarFile(output);
		for (final JarEntry entry : new IteratorPlus<JarEntry>(jar.entries())) {
			final String path = entry.getName();
			assertTrue("Unexpected: " + path, set.remove(path));
		}
		jar.close();
		assertEquals("Missing: " + set, 0, set.size());
	}
}
