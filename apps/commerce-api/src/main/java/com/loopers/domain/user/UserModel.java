package com.loopers.domain.user;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "users")
public class UserModel extends BaseEntity {

    @Column(name = "name", nullable = false)
    @Comment("사용자 이름")
    private String name;

    protected UserModel() {}

    public UserModel(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
