package com.loopers.utils;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DatabaseCleanUp {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public void deleteAllEntities() {
        entityManager.flush();
        for (var entityType : entityManager.getMetamodel().getEntities()) {
            var entities = entityManager.createQuery(
                "select e from " + entityType.getName() + " e", entityType.getJavaType()).getResultList();
            entities.forEach(entityManager::remove);
        }
        entityManager.flush();
        entityManager.clear();
    }
}
