package com.example.springbootapp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.example.springbootapp.storage.S3StorageService;
import com.example.springbootapp.users.UserStore;

@Component
@Order(0)
public class BackendInitializer implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger("lowops");

    private final DatabaseSupport databaseSupport;
    private final S3StorageService s3StorageService;
    private final UserStore userStore;

    public BackendInitializer(
            DatabaseSupport databaseSupport,
            S3StorageService s3StorageService,
            UserStore userStore
    ) {
        this.databaseSupport = databaseSupport;
        this.s3StorageService = s3StorageService;
        this.userStore = userStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        databaseSupport.initDatabase();
        s3StorageService.initS3();
        userStore.logBackendMode();
        logger.info("Backend initialization complete");
    }
}
