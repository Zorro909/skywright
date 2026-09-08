package de.zorro909.skywright.backend.worker;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts a standalone worker from either the packaged application or the test classpath.
 */
public final class WorkerProcessCommand {

	private WorkerProcessCommand() {
	}

	public static List<String> command(Class<?> main, List<String> arguments) {
		String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
		var result = new ArrayList<String>();
		result.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
		if (classPath.endsWith(".jar") && !classPath.contains(System.getProperty("path.separator"))) {
			result.add("-Dloader.main=" + main.getName());
			result.add("-cp");
			result.add(classPath);
			result.add("org.springframework.boot.loader.launch.PropertiesLauncher");
		}
		else {
			result.add("-cp");
			result.add(classPath);
			result.add(main.getName());
		}
		result.addAll(arguments);
		return result;
	}

}
