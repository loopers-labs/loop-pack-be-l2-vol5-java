package com.loopers.user.domain;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Embedded
    private Point point = new Point(0);

    public Point getPoint() {
        return point;
    }

    public void charge(long amount) {
        this.point = point.charge(amount);
    }

    public void pay(long amount) {
        this.point = point.pay(amount);
    }
}
