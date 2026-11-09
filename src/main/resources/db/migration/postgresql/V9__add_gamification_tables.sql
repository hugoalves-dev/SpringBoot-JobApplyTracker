CREATE TABLE user_gamification (
    user_id UUID NOT NULL,
    current_xp BIGINT NOT NULL DEFAULT 0,
    level INT NOT NULL DEFAULT 1,
    streak_days INT NOT NULL DEFAULT 0,
    last_activity_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_user_gamification PRIMARY KEY (user_id),
    CONSTRAINT fk_user_gamification_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_user_gamification_level ON user_gamification (level);

CREATE TABLE achievements (
    id UUID NOT NULL,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(150) NOT NULL,
    description VARCHAR(255) NOT NULL,
    icon VARCHAR(50) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_achievements PRIMARY KEY (id),
    CONSTRAINT uk_achievements_code UNIQUE (code)
);

CREATE TABLE user_achievements (
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    achievement_id UUID NOT NULL,
    achieved_at TIMESTAMP NOT NULL,
    metadata_json TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_user_achievements PRIMARY KEY (id),
    CONSTRAINT uk_user_achievements_user_achievement UNIQUE (user_id, achievement_id),
    CONSTRAINT fk_user_achievements_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_achievements_achievement FOREIGN KEY (achievement_id) REFERENCES achievements (id) ON DELETE CASCADE
);

CREATE INDEX idx_user_achievements_user_id ON user_achievements (user_id);
CREATE INDEX idx_user_achievements_achievement_id ON user_achievements (achievement_id);

