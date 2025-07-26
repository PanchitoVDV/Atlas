package be.esmay.atlas.base.database;

import be.esmay.atlas.base.activity.ServerActivity;
import be.esmay.atlas.base.config.impl.AtlasConfig;
import be.esmay.atlas.base.utils.Logger;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.Getter;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hibernate.service.ServiceRegistry;

import javax.sql.DataSource;
import java.io.File;
import java.util.Properties;

public final class DatabaseManager {

    @Getter
    private DataSource dataSource;
    
    @Getter
    private SessionFactory sessionFactory;
    
    private final AtlasConfig.Database databaseConfig;

    public DatabaseManager(AtlasConfig.Database databaseConfig) {
        this.databaseConfig = databaseConfig;
    }

    public void initialize() {
        try {
            this.setupDataSource();
            this.setupSessionFactory();
            Logger.info("Database initialized successfully with type: {}", this.databaseConfig.getType());
        } catch (Exception e) {
            Logger.error("Failed to initialize database", e);
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    private void setupDataSource() {
        HikariConfig config = new HikariConfig();
        
        String dbType = this.databaseConfig.getType().toLowerCase();
        
        switch (dbType) {
            case "h2" -> {
                String dbPath = this.databaseConfig.getPath();
                File dbFile = new File(dbPath).getParentFile();
                if (dbFile != null && !dbFile.exists()) {
                    boolean created = dbFile.mkdirs();
                    if (!created) {
                        Logger.warn("Failed to create database directory: {}", dbFile.getAbsolutePath());
                    }
                }
                
                config.setJdbcUrl("jdbc:h2:file:./" + dbPath + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
                config.setDriverClassName("org.h2.Driver");
                config.setUsername("sa");
                config.setPassword("");
            }
            case "mysql" -> {
                config.setJdbcUrl(this.databaseConfig.getUrl());
                config.setDriverClassName("com.mysql.cj.jdbc.Driver");
                config.setUsername(this.databaseConfig.getUsername());
                config.setPassword(this.databaseConfig.getPassword());
            }
            case "postgresql" -> {
                config.setJdbcUrl(this.databaseConfig.getUrl());
                config.setDriverClassName("org.postgresql.Driver");
                config.setUsername(this.databaseConfig.getUsername());
                config.setPassword(this.databaseConfig.getPassword());
            }
            default -> throw new IllegalArgumentException("Unsupported database type: " + dbType);
        }
        
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        
        config.setLeakDetectionThreshold(0);
        
        System.setProperty("com.zaxxer.hikari.slf4j", "false");
        
        this.dataSource = new HikariDataSource(config);
    }

    private void setupSessionFactory() {
        Configuration configuration = new Configuration();
        
        Properties properties = new Properties();
        String dbType = this.databaseConfig.getType().toLowerCase();
        
        switch (dbType) {
            case "h2" -> {
                properties.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
                properties.setProperty("hibernate.hbm2ddl.auto", "update");
            }
            case "mysql" -> {
                properties.setProperty("hibernate.dialect", "org.hibernate.dialect.MySQLDialect");
                properties.setProperty("hibernate.hbm2ddl.auto", "update");
            }
            case "postgresql" -> {
                properties.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
                properties.setProperty("hibernate.hbm2ddl.auto", "update");
            }
        }
        
        properties.setProperty("hibernate.show_sql", "false");
        properties.setProperty("hibernate.format_sql", "false");
        properties.setProperty("hibernate.use_sql_comments", "false");
        properties.setProperty("hibernate.connection.provider_class", "org.hibernate.hikaricp.internal.HikariCPConnectionProvider");
        properties.setProperty("hibernate.hikari.dataSource", "provided");
        
        configuration.setProperties(properties);
        
        configuration.addAnnotatedClass(ServerActivity.class);
        
        configuration.getStandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.datasource", this.dataSource);
        
        ServiceRegistry serviceRegistry = new StandardServiceRegistryBuilder()
            .applySettings(configuration.getProperties())
            .applySetting("hibernate.connection.datasource", this.dataSource)
            .build();
            
        this.sessionFactory = configuration.buildSessionFactory(serviceRegistry);
    }

    public void shutdown() {
        try {
            if (this.sessionFactory != null && !this.sessionFactory.isClosed()) {
                this.sessionFactory.close();
                Logger.debug("SessionFactory closed");
            }
            
            if (this.dataSource instanceof HikariDataSource hikariDataSource) {
                hikariDataSource.close();
                Logger.debug("DataSource closed");
            }
            
            Logger.info("Database shutdown completed");
        } catch (Exception e) {
            Logger.error("Error during database shutdown", e);
        }
    }
}