package com.loopers.utils;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

@Component
public class DatabaseCleanUp implements InitializingBean {

    @PersistenceContext
    private EntityManager entityManager;

    private final List<String> tableNames = new ArrayList<>();

    /**
     * @Entity 테이블뿐 아니라 @ElementCollection(@CollectionTable)로 매핑된 자식 테이블도 찾아서 넣는다.
     * 이 테이블들은 @Embeddable(예: OrderItem)에 매핑돼 JPA metamodel의 "entity" 목록엔 안 잡히는데,
     * 안 비우면 MySQL TRUNCATE가 부모 테이블의 auto-increment를 리셋시키는 것과 맞물려
     * 재사용된 부모 id에 이전 테스트의 자식 행이 그대로 섞여 보이는 문제가 생긴다.
     */
    @Override
    public void afterPropertiesSet() {
        entityManager.getMetamodel().getEntities().stream()
            .filter(entity -> entity.getJavaType().getAnnotation(Entity.class) != null)
            .forEach(entity -> {
                Class<?> javaType = entity.getJavaType();
                tableNames.add(javaType.getAnnotation(Table.class).name());
                for (Field field : javaType.getDeclaredFields()) {
                    CollectionTable collectionTable = field.getAnnotation(CollectionTable.class);
                    if (collectionTable != null) {
                        tableNames.add(collectionTable.name());
                    }
                }
            });
    }

    @Transactional
    public void truncateAllTables() {
        entityManager.flush();
        entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate();

        for (String table : tableNames) {
            entityManager.createNativeQuery("TRUNCATE TABLE `" + table + "`").executeUpdate();
        }

        entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS = 1").executeUpdate();
    }
}
