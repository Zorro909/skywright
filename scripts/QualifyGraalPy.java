import java.nio.file.Path;
import org.graalvm.polyglot.Context;
import org.graalvm.python.embedding.GraalPyResources;

// Run with the effective Maven runtime, not an absolute launcher captured in a cache.
class QualifyGraalPy {
    public static void main(String[] args) {
        try (var context = Context.newBuilder("python")
                .apply(GraalPyResources.forExternalDirectory(Path.of(args[0])))
                .allowAllAccess(true)
                .option("python.PosixModuleBackend", "native")
                .arguments("python", new String[] {"qualify-native-imports"}).build()) {
            context.eval("python", "import aiohttp, cryptography, numpy, pandas, psutil, sky, uvloop, watchfiles");
            String version = context.eval("python", "sky.__version__").asString();
            if (!args[1].equals(version)) {
                throw new IllegalStateException("Packaged SkyPilot version mismatch: " + version);
            }
            System.out.println("Qualified native imports with SkyPilot " + version);
            // Import qualification only. Packaged application tests own orderly shutdown.
            Runtime.getRuntime().halt(0);
        }
    }
}
