package ruc.db;

import ruc.db.analyzer.QueryInstantiate;
import ruc.db.analyzer.SchemaStatsExtractor;
import ruc.db.analyzer.TaskConfigurator;
import ruc.db.generator.DataGenerator;
import ruc.db.schema.DDLGenerator;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "DBRepro",
        version = {"${COMMAND-NAME} 1.0.0",
                "JVM: ${java.version} (${java.vendor} ${java.vm.name} ${java.vm.version})",
                "OS: ${os.name} ${os.version} ${os.arch}"},
        description = "tool for generating test database", sortOptions = false,
        subcommands = {TaskConfigurator.class, SchemaStatsExtractor.class, DataGenerator.class, DDLGenerator.class, QueryInstantiate.class},
        mixinStandardHelpOptions = true, usageHelpAutoWidth = true,
        header = {
                "@|blue  ____  ____  ____                      |@",
                "@|blue |  _ \\| __ )|  _ \\ ___ _ __  _ __ ___   |@",
                "@|blue | | | |  _ \\| |_) / _ \\ '_ \\| '__/ _ \\  |@",
                "@|blue | |_| | |_) |  _ <  __/ |_) | | | (_) | |@",
                "@|blue |____/|____/|_| \\_\\___| .__/|_|  \\___/  |@",
                "@|blue                       |_|              |@"}
)
public class DBReproApp {
    public static void main(String... args) {
        int exitCode = new CommandLine(new DBReproApp()).execute(args);
        System.exit(exitCode);
    }
}
