package com.example.springbootapp.config;

import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.example.springbootapp.storage.S3StorageService;
import com.example.springbootapp.users.UserStore;

@Component
public class BackendSupport {

    private static final Logger logger = LoggerFactory.getLogger("lowops");

    private final DatabaseSupport databaseSupport;
    private final S3StorageService s3StorageService;
    private final ObjectProvider<UserStore> userStoreProvider;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile boolean initialized;

    public BackendSupport(
            DatabaseSupport databaseSupport,
            S3StorageService s3StorageService,
            ObjectProvider<UserStore> userStoreProvider
    ) {
        this.databaseSupport = databaseSupport;
        this.s3StorageService = s3StorageService;
        this.userStoreProvider = userStoreProvider;
    }

    public void ensureBackends() {
        if (initialized) {
            return;
        }
        lock.lock();
        try {
            if (initialized) {
                return;
            }
            databaseSupport.initDatabase();
            s3StorageService.initS3();
            userStoreProvider.getObject().logBackendMode();
            initialized = true;
            logger.info("Backend initialization complete");
        } finally {
            lock.unlock();
        }
    }
}
