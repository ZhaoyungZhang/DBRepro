# DBRepro

DBRepro is an end-to-end framework for **high-fidelity reproduction of slow queries** under strict data privacy. Without access to raw user data, it synthesizes a **synthetic proxy database** from non-intrusive metadata only—schema, column-level catalog statistics, target SQL statements, and their physical execution plans—so that the test optimizer tends to choose the same physical plans as in production, enabling reliable offline diagnosis and tuning.

![DBRepro Architecture](assets/DBRepro.svg)

## Overview

DBRepro formulates database generation as a **constrained distribution synthesis** problem: it bootstraps a global data distribution from catalog statistics, extracts cardinality constraints from the execution context of the target queries, and progressively refines the distribution to satisfy those constraints while preserving global statistics as much as possible.

The pipeline is:

1. **Runtime Context Capturer**  
   Collects schema \(H\), statistics \(S\), slow queries \(Q\), and physical plans \(P\) from the production side. By supporting both direct query and histogram-based extraction, it can achieve O(1) time complexity independent of table sizes.

2. **Analyzer**  
   Converts raw plans into Annotated Query Plans (AQPs) and extracts declarative **cardinality constraints**, including Selection Cardinality Constraints (SCCs) and Join Cardinality Constraints (JCCs).

3. **Data distribution and constraint solving**  
   - **Data Distribution Manager**: initializes global distributions for primary-key and non-key columns from statistics.  
   - **Constraint Manager (hybrid solving)**: for SCCs, uses heuristic probabilistic inference and **distribution fusion** to enforce local cardinalities with minimal deviation from the original statistics; for JCCs, augments a Constraint Programming (CP) model with **Scaling Factors** to handle complex, non-unique joins and mitigate cardinality fan-out.

4. **Physical data generation**  
   Materializes the synthetic database \(\tilde{D}\) from the final distribution for isolated replay of slow-query behavior.

High-fidelity goals achieved include: schema consistency, statistical consistency, plan structural consistency, per-operator cardinality consistency, latency-proportion consistency on key operators, and limited generalization to unseen queries in the same distributional setting.

## Environment Setup

DBRepro currently supports PostgreSQL versions 12 through 16 and includes an adapter for the KingbaseES V8 series.

DBRepro requires **Java 21 or newer** and **Maven**. Verify both tools before building:

```bash
mvn -version
java -version
```

Build and verify the shaded JAR from the repository root:

```bash
./build_dbrepro_jar.sh
```

The script compiles the project and creates `target/DBRepro-0.1.0.jar`. It also verifies both command-line entry points:

```bash
# Statistics extraction and DDL generation
java -jar target/DBRepro-0.1.0.jar --help

# Prepare, instantiate, generate, and create stages
java -cp "target/DBRepro-0.1.0.jar:lib/*" ruc.db.DBReproApp --help
```

Run the complete unit test suite separately:

```bash
mvn test
```

*Note: For configurations that modify PostgreSQL to identify IndexScan cardinalities more accurately, refer to the upstream [Mirage README](https://github.com/DBHammer/Mirage/blob/main/README.md).*

### KingbaseES Support

DBRepro has been adapted and tested with the KingbaseES V8 series. The KingbaseES JDBC driver is proprietary and is therefore not redistributed in this repository. KingbaseES users must obtain `kingbase8-9.0.0.jar` from the JDBC directory of their official KingbaseES installation and place it under `lib/`:

```bash
cp <KINGBASE_INSTALL_DIR>/JDBC/kingbase8-9.0.0.jar lib/
```

Commands that connect to KingbaseES must include the external driver on the runtime classpath:

```bash
java -cp "target/DBRepro-0.1.0.jar:lib/*" \
  ruc.db.rsgen.RSGenMainCLI <command> <options>

java -cp "target/DBRepro-0.1.0.jar:lib/*" \
  ruc.db.DBReproApp <stage> <options>
```

### Adapting Another Database

Adding another JDBC-compatible database requires both connection and execution-plan integration:

1. Add a connector under `src/main/java/ruc/db/dbconnector/adapter/` by extending `DbConnector`. Implement the JDBC URL settings, session initialization, `EXPLAIN` command, metadata access, and any database-specific catalog behavior.
2. Add the database type to `src/main/java/ruc/db/analyzer/TouchstoneDbType.java` and `ConfigManager.DatabaseType` in `src/main/java/ruc/db/utils/ConfigManager.java`.
3. Register the connector in the database-selection switches in `TaskConfigurator.java`, `SchemaStatsExtractor.java`, and `RSGenMainCLI.java`.
4. Select or implement an execution-plan analyzer under `src/main/java/ruc/db/analyzer/online/adapter/`. PostgreSQL-compatible JSON plans can usually reuse `PgAnalyzer`; other plan formats require a new `AbstractAnalyzer` implementation.
5. Provide the vendor JDBC driver as a Maven dependency when redistribution is permitted, or load a user-supplied JAR from `lib/` at runtime when it is not.

## DBRepro Workflow and Usage

The workflow for DBRepro follows a 5-step pipeline, incorporating statistical extraction and constraint solving based on the extracted statistics. Below are the standard 5 steps to run the DBRepro pipeline. You will need to replace the placeholders (like `<host>`, `<port>`, `<config.json>`, etc.) with your actual environment values.

### Configuration File (`config.json`)
Before starting the pipeline, you need to set up a JSON configuration file. It contains database connection details and directory paths. Here is an example configuration format:

```json
{
  "databaseConnectorConfig": {
    "databaseIp": "127.0.0.1",
    "databaseName": "ssb",
    "databasePort": "5432",
    "databasePwd": "<password>",
    "databaseUser": "<user>"
  },
  "queriesDirectory": "dbrepro_ssb/queriesDirectory",
  "resultDirectory": "dbrepro_ssb",
  "defaultSchemaName": "public"
}
```
- `databaseConnectorConfig`: Target database connection information.
- `queriesDirectory`: Path where the target SQL queries are located.
- `resultDirectory`: Path for storing query-analysis and data-generation outputs.
- `defaultSchemaName`: Default schema for the database (e.g., `public`).

Relative paths are resolved from the process working directory, so the commands below should be run from the repository root. A short RSGen configuration name such as `dbrepro_ssb` resolves to `conf/dbrepro_ssb.json`. To use another default configuration directory, pass `-Ddbrepro.config.dir=<directory>` to Java. An explicit path ending in `.json` can always be supplied directly.

### 1. Statistics Extraction
First, extract the catalog statistics and schema information from the target database.

```bash
java -jar target/DBRepro-0.1.0.jar \
  extract -h <host> -p <port> -d <database_name> -u <user> \
  -w <password> -o <output_directory> -r direct-query
```

*Note on the `-r` parameter:*
- `-r direct-query`: Executes direct aggregation queries on the target database to obtain information.
- `-r histogram`: Infers database information (such as `table_size` and column `min`/`max` values) directly from the database's built-in catalog statistics (histograms). This approach makes the extraction time independent of the actual number of rows in the tables, achieving **O(1) complexity** and minimizing the impact on the production database.

### 2. Prepare Stage (Query Analysis)
Parse the local execution plans.

```bash
java -cp "target/DBRepro-0.1.0.jar:lib/*" ruc.db.DBReproApp \
  prepare -c <config.json> -t <database_type>
```

*Note: You can also specify an execution plan directory using `--local-plan-json-dir <plan_directory>`, or specify a single execution plan using `--local-plan-json <plan_file>`.*

### 3. Instantiate Stage (Constraint Solving)
Select the cardinality constraint solving stage. In this phase, DBRepro uses heuristic probabilistic inference to solve SCCs, and utilizes the Iterative Proportional Fitting (IPF) algorithm to reconcile potential conflicts between the extracted catalog statistics and the local cardinality constraints, ensuring accurate parameter instantiation.

```bash
java -cp "target/DBRepro-0.1.0.jar:lib/*" ruc.db.DBReproApp \
  instantiate -c <output_directory> \
  --statistics <output_directory>/enhanced_column_statistics.json
```

### 4. Data Generation
Generate the synthetic data, making use of the enhanced column statistics to ensure distribution consistency.

```bash
java -cp "target/DBRepro-0.1.0.jar:lib/*" ruc.db.DBReproApp \
  generate -c <output_directory> -o <data_output_directory> -n 1 -i 0 \
  --statistics <output_directory>/enhanced_column_statistics.json
```

### 5. Generate DDL and Indexes
Finally, generate the schema creation statements (DDL) and execute them to create the final synthesized database. You can either generate the DDL files for manual execution or have DBRepro automatically create the database elements.

```bash
# Generate DDL files
java -jar target/DBRepro-0.1.0.jar \
  ddl -i <output_directory> -o <ddl_output_directory> -c <config.json>

# OR directly create database elements
java -cp "target/DBRepro-0.1.0.jar:lib/*" ruc.db.DBReproApp \
  create -c <output_directory> -d <database_name> -o <ddl_output_directory>
```

### Example Artifacts
In `src/test/resources/data/query-instantiation`, we provide example artifacts for both the TPC-H and SSB benchmarks. These files showcase the declarative cardinality constraints extracted after the query analysis stage, as well as intermediate artifacts generated during the hybrid constraint solving process.

## Built with Mirage

**DBRepro** uses the open-source [Mirage](https://github.com/DBHammer/Mirage) framework. We extend its capabilities by introducing global data distributions, scaling factors for complex joins, and integrating comprehensive catalog statistics to achieve high-fidelity slow query reproduction. 

For additional details on the base functionality (such as detailed configuration options or manual source code patches), please refer to the upstream [Mirage README](https://github.com/DBHammer/Mirage/blob/main/README.md).


## Citation

If you use DBRepro in your research, please cite our ASE 2026 paper:

```bibtex
@inproceedings{zhang2026dbrepro,
  author    = {Zhaoyang Zhang and Shuang Liu and Dengfeng Xu and Wei Lu and Jianquan Leng and Sheng Du and Xiaoyong Du},
  title     = {{DBRepro}: Automated Database Synthesis via a Hybrid Constraint-Solving Approach for Reproducing Slow Queries},
  booktitle = {Proceedings of the 41st IEEE/ACM International Conference on Automated Software Engineering (ASE '26)},
  year      = {2026},
  month     = oct,
  publisher = {Association for Computing Machinery},
  address   = {New York, NY, USA},
  location  = {Munich, Germany},
  doi       = {10.1145/3832783.3834463},
  isbn      = {979-8-4007-2882-2/2026/10},
  url       = {https://doi.org/10.1145/3832783.3834463}
}
```
