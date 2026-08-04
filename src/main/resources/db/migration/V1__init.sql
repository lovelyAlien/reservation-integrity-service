CREATE TABLE room_inventory (
    id               BIGSERIAL PRIMARY KEY,
    room_type_id     VARCHAR(50)  NOT NULL,
    stay_date        DATE         NOT NULL,
    total_stock      INT          NOT NULL,
    available_stock  INT          NOT NULL,
    updated_at       TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_room_inventory UNIQUE (room_type_id, stay_date)
);

CREATE TABLE reservation (
    id                           BIGSERIAL PRIMARY KEY,
    channel                      VARCHAR(30)  NOT NULL,
    external_reservation_id      VARCHAR(100) NOT NULL,
    room_type_id                 VARCHAR(50)  NOT NULL,
    stay_date                    DATE         NOT NULL,
    status                       VARCHAR(20)  NOT NULL,
    simulate_downstream_failure  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at                   TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_reservation_channel_external UNIQUE (channel, external_reservation_id)
);

CREATE TABLE outbox_event (
    id              BIGSERIAL PRIMARY KEY,
    reservation_id  BIGINT       NOT NULL REFERENCES reservation(id),
    event_type      VARCHAR(30)  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count     INT          NOT NULL DEFAULT 0,
    max_retry       INT          NOT NULL DEFAULT 5,
    next_retry_at   TIMESTAMP    NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT now()
);
