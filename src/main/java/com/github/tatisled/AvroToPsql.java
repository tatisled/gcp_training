package com.github.tatisled;

import com.github.tatisled.config.ConnectionConfig;
import com.google.common.collect.ImmutableMap;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.AvroIO;
import org.apache.beam.sdk.io.jdbc.JdbcIO;
import org.apache.beam.sdk.options.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.beans.PropertyVetoException;
import java.sql.Types;
import java.util.Objects;

import static com.github.tatisled.util.AvroUtils.getAvroSchema;

public class AvroToPsql {

    private static final Logger LOG = LoggerFactory.getLogger(AvroToPsql.class);

    private static final ImmutableMap<Schema.Type, SqlTypeWithName> typesMap = ImmutableMap.<Schema.Type, SqlTypeWithName>builder()
            .put(Schema.Type.LONG, new SqlTypeWithName(Types.BIGINT, "BIGINT"))
            .put(Schema.Type.STRING, new SqlTypeWithName(Types.VARCHAR, "VARCHAR"))
            .put(Schema.Type.INT, new SqlTypeWithName(Types.INTEGER, "INTEGER"))
            .put(Schema.Type.DOUBLE, new SqlTypeWithName(Types.DOUBLE, "DOUBLE PRECISION"))
            .build();

    private static final String CREATE_TABLE_QUERY = "CREATE TABLE IF NOT EXISTS test_table (%s)";
    private static final String INSERT_QUERY = "INSERT INTO test_table VALUES (%s)";

    static class SqlTypeWithName {
        int typeCode;
        String typeName;

        SqlTypeWithName(int type, String name) {
            this.typeCode = type;
            this.typeName = name;
        }

        public int getTypeCode() {
            return typeCode;
        }

        public String getTypeName() {
            return typeName;
        }

    }

    public interface AvroToPsqlOptions extends PipelineOptions, DataflowPipelineOptions {
        @Description("Input path")
        @Validation.Required
        ValueProvider<String> getInputPath();

        @SuppressWarnings("unused")
        void setInputPath(ValueProvider<String> path);
    }

    private String getQueryParams(Schema avroSchema) {
        StringBuilder sb = new StringBuilder();
        int fieldsCount = Objects.requireNonNull(avroSchema).getFields().size();
        while (fieldsCount > 1) {
            sb.append("?, ");
            fieldsCount--;
        }
        sb.append("?");

        return String.format(INSERT_QUERY, sb.toString());
    }

//    private String getColumnsWithTypes(Schema avroSchema) {
//        StringBuilder sb = new StringBuilder();
//        int totalFields = avroSchema.getFields().size();
//        int counter = 0;
//        while (counter < totalFields) {
//            Schema.Field currField = avroSchema.getFields().get(counter);
//            Schema.Type currType = currField.schema().getTypes().get(0).getType();
//            sb.append(currField.name()).append(" ").append(typesMap.get(currType).getTypeName());
//            if (counter != totalFields - 1) {
//                sb.append(", ");
//            }
//            counter++;
//        }
//        return String.format(CREATE_TABLE_QUERY, sb.toString());
//    }

    private final JdbcIO.PreparedStatementSetter<GenericRecord> rowParser = (row, query) -> {
        Schema schema = row.getSchema();
        int totalFields = Objects.requireNonNull(schema).getFields().size();
        int counter = 0;
        while (counter < totalFields) {
            Schema.Field currField = schema.getFields().get(counter);
            Schema.Type currType = currField.schema().getTypes().get(0).getType();
            query.setObject(counter + 1, row.get(currField.name()), typesMap.get(currType).getTypeCode());
            counter++;
        }
    };

    protected void run(AvroToPsqlOptions options) throws PropertyVetoException {
        Pipeline pipeline = Pipeline.create(options);

        JdbcIO.DataSourceConfiguration config = JdbcIO.DataSourceConfiguration.create(ConnectionConfig.getConnectionConfig());

        Schema schema = getAvroSchema();

        pipeline
                .apply("Read Avro from DataStorage", AvroIO.readGenericRecords(schema)
                        .from(options.getInputPath()))
                .apply(JdbcIO.<GenericRecord>write()
                                .withDataSourceConfiguration(config)
//                                .withStatement(getColumnsWithTypes(schema))
                                .withStatement(getQueryParams(schema))
                                .withPreparedStatementSetter(rowParser)
                );

        pipeline.run().waitUntilFinish();
    }

    public static void main(String[] args) throws PropertyVetoException {
        AvroToPsqlOptions options =
                PipelineOptionsFactory.fromArgs(args).withValidation().as(AvroToPsqlOptions.class);

        new AvroToPsql().run(options);
    }


}
