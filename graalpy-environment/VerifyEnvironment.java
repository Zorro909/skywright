import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.graalvm.polyglot.Context;
import org.graalvm.python.embedding.GraalPyResources;

/** A disposable import/version probe, not a qualification of embedded shutdown. */
public final class VerifyEnvironment {
    public static void main(String[] arguments) {
        try {
            if (arguments.length != 4) throw new IllegalArgumentException("Expected resources, receipt and effective versions");
            Path resources = Path.of(arguments[0]).toAbsolutePath();
            Path receipt = Path.of(arguments[1]).toAbsolutePath();
            Files.deleteIfExists(receipt);
            Context context = Context.newBuilder("python")
                .apply(GraalPyResources.forExternalDirectory(resources))
                .allowAllAccess(true)
                .option("python.PosixModuleBackend", "native")
                .arguments("python", new String[] {"skywright-environment-probe", arguments[2], arguments[3], resources.toString()})
                .build();
            if (!context.getEngine().getVersion().equals(arguments[2]))
                throw new IllegalStateException("Maven-resolved GraalPy runtime differs from the expected version");
            String observed = context.eval("python", """
                import sys
                sys.dont_write_bytecode = True
                import importlib.metadata
                import json
                import re
                from pathlib import Path
                import sys
                import aiohttp
                import cryptography
                import numpy
                import pandas
                import psutil
                import sky
                import uvloop
                import watchfiles

                runtime = '.'.join(str(part) for part in sys.implementation.version[:3])
                assert sys.implementation.name == 'graalpy', 'Unexpected Python implementation'
                assert runtime == sys.argv[1], 'Unexpected GraalPy version'
                assert sky.__version__ == sys.argv[2], 'Unexpected SkyPilot version'
                library = (Path(sys.argv[3]) / 'venv' / 'lib').resolve()
                for module in (aiohttp, cryptography, numpy, pandas, psutil, sky, uvloop, watchfiles):
                    assert Path(module.__file__).resolve().is_relative_to(library), 'Import outside packaged environment'
                packages = {}
                for distribution in importlib.metadata.distributions():
                    assert Path(distribution.locate_file('')).resolve().is_relative_to(library), 'Distribution outside packaged environment'
                    name = re.sub(r'[-_.]+', '-', distribution.metadata['Name']).lower()
                    assert name not in packages, 'Duplicate installed distribution'
                    packages[name] = distribution.version
                json.dumps({'implementation': sys.implementation.name, 'graalpy': runtime,
                            'python': '.'.join(str(part) for part in sys.version_info[:3]),
                            'skypilot': sky.__version__, 'packages': packages}, sort_keys=True)
                """).asString();
            byte[] bytes = observed.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 1024 * 1024) throw new IllegalStateException("Environment observation exceeds its bound");
            Files.createDirectories(receipt.getParent());
            Path pending = receipt.resolveSibling(receipt.getFileName() + ".pending");
            Files.write(pending, bytes);
            Files.move(pending, receipt, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // Preserve the prior standalone smoke's explicit import-only scope. The
            // long-lived backend's executor/socket/context shutdown is qualified elsewhere.
            Runtime.getRuntime().halt(0);
        }
        catch (Throwable failure) {
            System.err.println("GRAALPY_ENVIRONMENT_OBSERVATION_FAILED: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            Runtime.getRuntime().halt(1);
        }
    }
}
