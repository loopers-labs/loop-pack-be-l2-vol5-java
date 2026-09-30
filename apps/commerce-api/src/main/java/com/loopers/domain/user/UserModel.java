package com.loopers.domain.user;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "user")
public class UserModel extends BaseEntity {

    @Embedded
    private Point point;

    public UserModel() {
        this.point = new Point();
    }

    public Point getPoint() {
        return point;
    }
}
