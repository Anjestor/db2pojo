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

            getLog().info("✅ JPA Entities with relationships generated in: " + outputDirectory);
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

        // Collect foreign keys
        Map<String, String> fkToTable = new HashMap<>();
        try (ResultSet fkRs = meta.getImportedKeys(null, null, tableName)) {
            while (fkRs.next()) {
                String fkColumn = fkRs.getString("FKCOLUMN_NAME");
                String pkTable = fkRs.getString("PKTABLE_NAME");
                fkToTable.put(fkColumn, pkTable);
            }
        }

        String primaryKeyColumn = null;
        try (ResultSet pkRs = meta.getPrimaryKeys(null, null, tableName)) {
            if (pkRs.next()) primaryKeyColumn = pkRs.getString("COLUMN_NAME");
        }

        StringBuilder gettersSetters = new StringBuilder();

        try (ResultSet rs = meta.getColumns(null, null, tableName, null)) {
            while (rs.next()) {
                String columnName = rs.getString("COLUMN_NAME");
                String columnType = rs.getString("TYPE_NAME");
                int size = rs.getInt("COLUMN_SIZE");

                // Foreign key case → generate relation instead of primitive field
                if (fkToTable.containsKey(columnName)) {
                    String targetTable = fkToTable.get(columnName);
                    String targetClass = toCamelCase(targetTable, true);
                    String fieldName = toCamelCase(targetTable, false);

                    classBuilder.append("    @ManyToOne(fetch = FetchType.LAZY)\n")
                            .append("    @JoinColumn(name = \"").append(columnName).append("\")\n")
                            .append("    private ").append(targetClass).append(" ").append(fieldName).append(";\n\n");

                    // getter
                    gettersSetters.append("    public ").append(targetClass)
                            .append(" get").append(toCamelCase(targetTable, true)).append("() {\n")
                            .append("        return ").append(fieldName).append(";\n    }\n\n");

                    // setter
                    gettersSetters.append("    public void set").append(toCamelCase(targetTable, true))
                            .append("(").append(targetClass).append(" ").append(fieldName)
                            .append(") {\n        this.").append(fieldName).append(" = ").append(fieldName)
                            .append(";\n    }\n\n");
                } else {
                    String javaType = mapSqlTypeToJava(columnType, size);
                    String fieldName = toCamelCase(columnName, false);

                    classBuilder.append("    @Column(name = \"").append(columnName).append("\")\n");
                    if (columnName.equalsIgnoreCase(primaryKeyColumn)) {
                        classBuilder.append("    @Id\n");
                        if (columnType.toUpperCase(Locale.ROOT).contains("SERIAL")
                            || columnType.toUpperCase(Locale.ROOT).contains("IDENTITY")) {
                            classBuilder.append("    @GeneratedValue(strategy = GenerationType.IDENTITY)\n");
                        }
                    }

                    classBuilder.append("    private ").append(javaType)
                            .append(" ").append(fieldName).append(";\n\n");

                    // getter
                    gettersSetters.append("    public ").append(javaType)
                            .append(" get").append(toCamelCase(columnName, true)).append("() {\n")
                            .append("        return ").append(fieldName).append(";\n    }\n\n");

                    // setter
                    gettersSetters.append("    public void set").append(toCamelCase(columnName, true))
                            .append("(").append(javaType).append(" ").append(fieldName)
                            .append(") {\n        this.").append(fieldName).append(" = ").append(fieldName)
                            .append(";\n    }\n\n");
                }
            }
        }

        classBuilder.append(gettersSetters);
        classBuilder.append("}\n");

        String filePath = outputDirectory + "/" + className + ".java";
        try (FileWriter writer = new FileWriter(filePath)) {
            writer.write(classBuilder.toString());
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
