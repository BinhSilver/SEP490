package com.jlas.conjugation;

import com.jlas.JlasApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypesScanner;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VerbPracticeEntityScanTest {
    @Test
    void applicationScanRegistersBothPracticeEntities() {
        var packages = JlasApplication.class.getAnnotation(EntityScan.class).basePackages();
        var managed = new PersistenceManagedTypesScanner(new DefaultResourceLoader()).scan(packages);

        assertTrue(managed.getManagedClassNames().contains(VerbPracticeSession.class.getName()),
                "The application must discover the session entity before creating its repository");
        assertTrue(managed.getManagedClassNames().contains(VerbPracticeProgress.class.getName()),
                "The application must discover the progress entity before creating its repository");
    }
}
