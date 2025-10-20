package com.example.codegen;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import java.io.File;
import java.io.FileWriter;
import java.sql.*;
import java.util.*;

@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class JpaEntityGeneratorMojo extends AbstractMojo {

    @Parameter(property = "jdbcUrl", required = true)
    private String jdbcUrl;

    @Parameter(property = "username", required = true)
    private String username;

    @Parameter(property = "password", required = true)
    private String password;

    @Parameter(property = "packageName", required = true)
    private String packageName;

    @Parameter(property = "outputDirectory", defaultValue = "${project.build.directory}/generated-sources/jpa")
    private String outputDirectory;

    public void execute() throws MojoExecutionException {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
            DatabaseMetaData meta = conn.getMetaData();
            new File(outputDirectory).mkdirs();

            try (ResultSet tables = meta.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    String tableName = tables.getString("TABLE_NAME");
                    generateEntity(meta, tableName);
                }
            }

            getLog().info("✅ JPA Entities generated with composite PK & multi-FK support in: " + outputDirectory);
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to generate entities", e);
        }
    }

    private void generateEntity(DatabaseMetaData meta, String tableName) throws Exception {
        String className = toCamelCase(tableName, true);
        StringBuilder classBuilder = new StringBuilder();

        classBuilder.append("package ").append(packageName).append(";\n\n")
                .append("import jakarta.persistence.*;\n")
                .append("import java.io.Serializable;\n\n")
                .append("@Entity\n")
                .append("@Table(name = \"").append(tableName).append("\")\n")
                .append("public class ").append(className).append(" implements Serializable {\n\n");

        // --- Collect PKs ---
        List<String> primaryKeys = new ArrayList<>();
        try (ResultSet pkRs = meta.getPrimaryKeys(null, null, tableName)) {
            while (pkRs.next()) primaryKeys.add(pkRs.getString("COLUMN_NAME"));
        }

        // --- Collect foreign keys ---
        Map<String, String> fkToTable = new LinkedHashMap<>();
        try (ResultSet fkRs = meta.getImportedKeys(null, null, tableName)) {
            while (fkRs.next()) {
                String fkColumn = fkRs.getString("FKCOLUMN_NAME");
                String pkTable = fkRs.getString("PKTABLE_NAME");
                fkToTable.put(fkColumn, pkTable);
            }
        }

        Map<String, Integer> fkTableCounts = new HashMap<>();
        StringBuilder gettersSetters = new StringBuilder();

        // --- Handle Composite Key ---
        boolean compositePk = primaryKeys.size() > 1;
        if (compositePk) {
            generateEmbeddableId(meta, tableName, className, primaryKeys);
            classBuilder.append("    @EmbeddedId\n")
                        .append("    private ").append(className).append("Id id;\n\n");
        }

        try (ResultSet rs = meta.getColumns(null, null, tableName, null)) {
            while (rs.next()) {
                String columnName = rs.getString("COLUMN_NAME");
                String columnType = rs.getString("TYPE_NAME");
                int size = rs.getInt("COLUMN_SIZE");

                // skip columns included in composite PK (they’re inside Embeddable)
                if (compositePk && primaryKeys.contains(columnName)) continue;

                // handle foreign keys
                if (fkToTable.containsKey(columnName)) {
                    String targetTable = fkToTable.get(columnName);
                    String targetClass = toCamelCase(targetTable, true);
                    int count = fkTableCounts.getOrDefault(targetTable, 0) + 1;
                    fkTableCounts.put(targetTable, count);

                    String fieldName = (count > 1)
                            ? toCamelCase(targetTable, false) + "By" + toCamelCase(columnName, true)
                            : toCamelCase(targetTable, false);

                    classBuilder.append("    @ManyToOne(fetch = FetchType.LAZY)\n")
                            .append("    @JoinColumn(name = \"").append(columnName).append("\")\n")
                            .append("    private ").append(targetClass).append(" ").append(fieldName).append(";\n\n");

                    gettersSetters.append("    public ").append(targetClass)
                            .append(" get").append(toCamelCase(fieldName, true)).append("() {\n")
                            .append("        return ").append(fieldName).append(";\n    }\n\n");

                    gettersSetters.append("    public void set").append(toCamelCase(fieldName, true))
                            .append("(").append(targetClass).append(" ").append(fieldName)
                            .append(") {\n        this.").append(fieldName).append(" = ").append(fieldName)
                            .append(";\n    }\n\n");
                } else if (!compositePk && primaryKeys.contains(columnName)) {
                    // simple PK
                    String javaType = mapSqlTypeToJava(columnType, size);
                    String fieldName = toCamelCase(columnName, false);

                    classBuilder.append("    @Id\n")
                            .append("    @GeneratedValue(strategy = GenerationType.IDENTITY)\n")
                            .append("    @Column(name = \"").append(columnName).append("\")\n")
                            .append("    private ").append(javaType).append(" ").append(fieldName).append(";\n\n");

                    gettersSetters.append("    public ").append(javaType)
                            .append(" get").append(toCamelCase(columnName, true)).append("() {\n")
                            .append("        return ").append(fieldName).append(";\n    }\n\n")
                            .append("    public void set").append(toCamelCase(columnName, true))
                            .append("(").append(javaType).append(" ").append(fieldName)
                            .append(") {\n        this.").append(fieldName).append(" = ").append(fieldName)
                            .append(";\n    }\n\n");
                } else {
                    // normal column
                    String javaType = mapSqlTypeToJava(columnType, size);
                    String fieldName = toCamelCase(columnName, false);

                    classBuilder.append("    @Column(name = \"").append(columnName).append("\")\n")
                            .append("    private ").append(javaType).append(" ").append(fieldName).append(";\n\n");

                    gettersSetters.append("    public ").append(javaType)
                            .append(" get").append(toCamelCase(columnName, true)).append("() {\n")
                            .append("        return ").append(fieldName).append(";\n    }\n\n")
                            .append("    public void set").append(toCamelCase(columnName, true))
                            .append("(").append(javaType).append(" ").append(fieldName)
                            .append(") {\n        this.").append(fieldName).append(" = ").append(fieldName)
                            .append(";\n    }\n\n");
                }
            }
        }

        classBuilder.append(gettersSetters);
        classBuilder.append("}\n");

        writeToFile(outputDirectory + "/" + className + ".java", classBuilder.toString());
    }

    /** Generates Embeddable ID class for composite PK */
    private void generateEmbeddableId(DatabaseMetaData meta, String tableName, String className, List<String> pkCols) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n")
          .append("import jakarta.persistence.*;\n")
          .append("import java.io.Serializable;\n\n")
          .append("@Embeddable\n")
          .append("public class ").append(className).append("Id implements Serializable {\n\n");

        StringBuilder gettersSetters = new StringBuilder();

        try (ResultSet rs = meta.getColumns(null, null, tableName, null)) {
            while (rs.next()) {
                String columnName = rs.getString("COLUMN_NAME");
                if (!pkCols.contains(columnName)) continue;

                String javaType = mapSqlTypeToJava(rs.getString("TYPE_NAME"), rs.getInt("COLUMN_SIZE"));
                String fieldName = toCamelCase(columnName, false);

                sb.append("    @Column(name = \"").append(columnName).append("\")\n")
                  .append("    private ").append(javaType).append(" ").append(fieldName).append(";\n\n");

                gettersSetters.append("    public ").append(javaType)
                        .append(" get").append(toCamelCase(columnName, true)).append("() {\n")
                        .append("        return ").append(fieldName).append(";\n    }\n\n")
                        .append("    public void set").append(toCamelCase(columnName, true))
                        .append("(").append(javaType).append(" ").append(fieldName)
                        .append(") {\n        this.").append(fieldName).append(" = ").append(fieldName)
                        .append(";\n    }\n\n");
            }
        }

        sb.append(gettersSetters);
        sb.append("}\n");

        writeToFile(outputDirectory + "/" + className + "Id.java", sb.toString());
    }

    private void writeToFile(String filePath, String content) throws Exception {
        File file = new File(filePath);
        file.getParentFile().mkdirs();
        try (FileWriter fw = new FileWriter(file)) {
            fw.write(content);
        }
    }

    private String toCamelCase(String input, boolean capitalizeFirst) {
        StringBuilder result = new StringBuilder();
        for (String part : input.split("_")) {
            if (part.isEmpty()) continue;
            String lower = part.toLowerCase(Locale.ROOT);
            result.append(capitalizeFirst || result.length() > 0
                    ? Character.toUpperCase(lower.charAt(0)) + lower.substring(1)
                    : lower);
            capitalizeFirst = true;
        }
        return result.toString();
    }

    private String mapSqlTypeToJava(String sqlType, int size) {
        sqlType = sqlType.toUpperCase(Locale.ROOT);
        switch (sqlType) {
            case "INT": case "INT4": case "INTEGER": return "Integer";
            case "BIGINT": case "INT8": return "Long";
            case "DECIMAL": case "NUMERIC": return "java.math.BigDecimal";
            case "FLOAT": case "FLOAT4": case "REAL": return "Float";
            case "FLOAT8": case "DOUBLE": return "Double";
            case "BOOLEAN": case "BOOL": return "Boolean";
            case "DATE": return "java.time.LocalDate";
            case "TIMESTAMP": case "TIMESTAMPTZ": return "java.time.LocalDateTime";
            case "CHAR": case "VARCHAR": case "TEXT": return "String";
            default: return "String";
        }
    }
                        }
