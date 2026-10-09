package com.loopers.user.domain;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User extends BaseEntity {

}
