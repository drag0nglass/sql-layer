package com.akiban.cserver.loader;

import com.akiban.ais.model.AkibaInformationSchema;
import com.akiban.ais.model.UserTable;
import com.akiban.cserver.store.PersistitStore;
import com.akiban.cserver.store.Store;

import java.sql.SQLException;
import java.util.*;

public class BulkLoader extends Thread
{
    // Thread interface

    @Override
    public void run()
    {
        try {
            DB db = dbHost == null ? null : new DB(dbHost, dbPort, dbUser, dbPassword);
            prepareWorkArea(db);
            tracker = new Tracker(db, artifactsSchema);
            tracker.info("Starting bulk load, source: %s@%s:%s, groups: %s, resume: %s, cleanup: %s",
                         dbUser, dbHost, dbPort, groups, resume, cleanup);
            if (taskGeneratorActions == null) {
                taskGeneratorActions = new MySQLTaskGeneratorActions(ais);
            }
            IdentityHashMap<UserTable, TableTasks> tableTasksMap =
                new TaskGenerator(this, taskGeneratorActions).generateTasks();
            DataGrouper dataGrouper = new DataGrouper(db, artifactsSchema, tracker);
            if (resume) {
                dataGrouper.resume();
            } else {
                dataGrouper.run(tableTasksMap);
            }
            new PersistitLoader(persistitStore, db, tracker).load(finalTasks(tableTasksMap));
            persistitStore.syncColumns();
            if (cleanup) {
                deleteWorkArea(db);
            }
            tracker.info("Loading complete");
            termination = new OKException();
        } catch (Exception e) {
            tracker.error("Bulk load terminated with exception", e);
            termination = e;
        }
    }

    // BulkLoader interface

    // For testing
    BulkLoader(AkibaInformationSchema ais,
                      String group,
                      String artifactsSchema,
                      TaskGenerator.Actions actions) throws ClassNotFoundException, SQLException
    {
        this.ais = ais;
        this.groups = Arrays.asList(group);
        this.artifactsSchema = artifactsSchema;
        this.taskGeneratorActions = actions;
    }

    public static synchronized BulkLoader start(Store store,
                                                AkibaInformationSchema ais,
                                                List<String> groups,
                                                String artifactsSchema,
                                                Map<String, String> sourceSchemas,
                                                String dbHost,
                                                int dbPort,
                                                String dbUser,
                                                String dbPassword,
                                                boolean resume,
                                                boolean cleanup) throws ClassNotFoundException, SQLException, InProgressException
    {
        if (inProgress == null) {
            inProgress = new BulkLoader(store,
                                        ais,
                                        groups,
                                        artifactsSchema,
                                        sourceSchemas,
                                        dbHost,
                                        dbPort,
                                        dbUser,
                                        dbPassword,
                                        resume,
                                        cleanup);
            inProgress.start();
        } else {
            throw new InProgressException();
        }
        return inProgress;
    }

    public static synchronized void done()
    {
        inProgress = null;
    }

    public static BulkLoader inProgress()
    {
        return inProgress;
    }

    public List<Event> recentEvents(int lastEventId) throws Exception
    {
        return tracker.recentEvents(lastEventId);
    }

    public BulkLoader(Store store,
                      AkibaInformationSchema ais,
                      List<String> groups,
                      String artifactsSchema,
                      Map<String, String> sourceSchemas,
                      String dbHost,
                      int dbPort,
                      String dbUser,
                      String dbPassword,
                      boolean resume,
                      boolean cleanup) throws ClassNotFoundException, SQLException
    {
        this.persistitStore = (PersistitStore) store;
        this.ais = ais;
        this.groups = groups;
        this.artifactsSchema = artifactsSchema;
        this.sourceSchemas = sourceSchemas;
        this.dbHost = dbHost;
        this.dbUser = dbUser;
        this.dbPort = dbPort;
        this.dbPassword = dbPassword;
        this.resume = resume;
        this.cleanup = cleanup;
    }

    public Exception termination()
    {
        return termination;
    }

    // For use by this package

    String artifactsSchema()
    {
        return artifactsSchema;
    }

    String sourceSchema(String targetSchema)
    {
        String sourceSchema = sourceSchemas.get(targetSchema);
        if (sourceSchema == null) {
            sourceSchema = targetSchema;
        }
        return sourceSchema;
    }

    List<String> groups()
    {
        return groups;
    }

    AkibaInformationSchema ais()
    {
        return ais;
    }

    // For use by this class

    private static List<GenerateFinalTask> finalTasks(IdentityHashMap<UserTable, TableTasks> tableTasksMap)
    {
        List<GenerateFinalTask> finalTasks = new ArrayList<GenerateFinalTask>();
        for (TableTasks tableTasks : tableTasksMap.values()) {
            GenerateFinalTask finalTask = tableTasks.generateFinal();
            if (finalTask != null) {
                finalTasks.add(finalTask);
            }
        }
        return finalTasks;
    }

    private void prepareWorkArea(DB db)
        throws SQLException
    {
        if (db != null) {
            DB.Connection connection = db.new Connection();
            try {
                connection.new DDL(TEMPLATE_DROP_BULK_LOAD_SCHEMA, artifactsSchema).execute();
                connection.new DDL(TEMPLATE_CREATE_BULK_LOAD_SCHEMA, artifactsSchema).execute();
                connection.new DDL(TEMPLATE_CREATE_TASKS_TABLE, artifactsSchema).execute();
            } finally {
                connection.close();
            }
        }
    }

    public void deleteWorkArea(DB db) throws SQLException
    {
        if (db != null) {
            DB.Connection connection = db.new Connection();
            try {
                tracker.info("Deleting work area");
                connection.new DDL(TEMPLATE_DROP_BULK_LOAD_SCHEMA, artifactsSchema).execute();
            } finally {
                connection.close();
            }
        }
    }

    // State

    private static final String TEMPLATE_DROP_BULK_LOAD_SCHEMA =
        "drop schema if exists %s";
    private static final String TEMPLATE_CREATE_BULK_LOAD_SCHEMA =
        "create schema %s";
    private static final String TEMPLATE_CREATE_TASKS_TABLE =
        "create table %s.task(" +
        "    task_id int auto_increment, " +
        "    task_type enum('GenerateFinalBySort', " +
        "                   'GenerateFinalByMerge', " +
        "                   'GenerateChild', " +
        "                   'GenerateParentBySort'," +
        "                   'GenerateParentByMerge') not null, " +
        "    state enum('waiting', 'started', 'completed') not null, " +
        "    time_sec double, " +
        "    user_table_schema varchar(64) not null, " +
        "    user_table_table varchar(64) not null, " +
        "    user_table_depth int not null, " +
        "    artifact_schema varchar(64) not null, " +
        "    artifact_table varchar(64) not null, " +
        "    command varchar(10000) not null, " +
        "    primary key(task_id)" +
        ")";

    // TODO: Once this is set, it is never unset. This enables tracking of progress by BulkLoaderClient even after the
    // TODO: bulk load is complete. But it doesn't allow for a second bulk load. Need to fix that.
    private static BulkLoader inProgress = null;

    private boolean resume = false;
    private boolean cleanup = true;
    private String dbHost;
    private int dbPort;
    private String dbUser;
    private String dbPassword;
    private String artifactsSchema;
    private List<String> groups;
    private Map<String, String> sourceSchemas;
    private PersistitStore persistitStore;
    private AkibaInformationSchema ais;
    private TaskGenerator.Actions taskGeneratorActions;
    private Exception termination = null;
    private Tracker tracker;

    public static class RuntimeException extends java.lang.RuntimeException
    {
        RuntimeException()
        {
        }

        RuntimeException(String message)
        {
            super(message);
        }

        RuntimeException(String message, Throwable th)
        {
            super(message, th);
        }
    }

    public static class InternalError extends java.lang.Error
    {
        InternalError(String message)
        {
            super(message);
        }
    }

    public static class DBSpawnFailedException extends RuntimeException
    {
        DBSpawnFailedException(String sql, Integer exitCode, Throwable th)
        {
            super(String.format("sql: %s, exit code: %s", sql, exitCode), th);
            this.exitCode = exitCode;
        }

        public int exitCode()
        {
            return exitCode;
        }

        private final int exitCode;
    }

    // Not actually thrown - indicates normal termination
    public static class OKException extends RuntimeException
    {
    }

    public static class InProgressException extends Exception
    {
    }
}
