package com.jobtracker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Single-row mutex: reservations lock it FOR UPDATE so checks and inserts are atomic across instances. */
@Entity
@Table(name = "assistant_quota_locks")
public class AssistantQuotaLock {
    public static final String GLOBAL = "assistant-global";

    @Id
    @Column(name = "lock_name", length = 64, nullable = false)
    private String name;

    protected AssistantQuotaLock() {}

    public AssistantQuotaLock(String name) { this.name = name; }

    public String getName() { return name; }
}
