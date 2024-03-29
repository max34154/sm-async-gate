CREATE SCHEMA IF NOT EXISTS %SCHEMA% AUTHORIZATION SA;
CREATE TABLE IF NOT EXISTS %SCHEMA%.REQUEST (
	REQ_ID VARCHAR(100) NOT NULL,
	USER_NAME VARCHAR(100) NOT NULL,
	EXECUTION_RETRIES INTEGER,
	OPEN_TIME TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
	"ACTION" VARCHAR(100) NOT NULL,
	PARAMETERS CLOB,
	STATUS CHAR(1) DEFAULT 'N' NOT NULL,
	SCHEDULE_NAME VARCHAR(100),
	RETRY_INTERVAL INTEGER,
	EXECUTION_MODE VARCHAR(60),
	LOCKED_BY VARCHAR(100),
	LOCK_TIME TIMESTAMP,
	ATTEMPT INTEGER DEFAULT 1,
	NEXT_RUN TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
	EXPIRE_AT TIMESTAMP,
	SERVICE VARCHAR(100),
	SUBJECT VARCHAR(60),
	TAG VARCHAR(100),
	CONSTRAINT REQUEST_PK PRIMARY KEY (REQ_ID)
);
CREATE UNIQUE INDEX IF NOT EXISTS REQUEST_PK ON %SCHEMA%.REQUEST (REQ_ID);
CREATE INDEX IF NOT EXISTS REQUEST_STSCHED ON %SCHEMA%.REQUEST(STATUS,SCHEDULE_NAME );
CREATE INDEX IF NOT EXISTS REQUEST_LOCKEDBY ON %SCHEMA%.REQUEST(LOCKED_BY);
CREATE TABLE IF NOT EXISTS %SCHEMA%.RESPONCE (
	RES_REQ_ID VARCHAR(100)  NOT NULL,
	"RESULT" CLOB,
	CLOSE_TIME TIMESTAMP,
	RES_STATUS INTEGER,
    FINISHED VARCHAR(2),
	CONSTRAINT RESPONCE_PK PRIMARY KEY (RES_REQ_ID)
);
CREATE UNIQUE INDEX IF NOT EXISTS RESPONCE_PK ON %SCHEMA%.RESPONCE (RES_REQ_ID);
CREATE TABLE IF NOT EXISTS %SCHEMA%.ATTACHMENT (
	ATT_ID VARCHAR(100)  NOT NULL,
	ATT_REQ_ID VARCHAR(100)  NOT NULL,
	SIZE INTEGER,
	NAME VARCHAR(100)  NOT NULL,
	CONTENT_TYPE VARCHAR(100),
	BODY BLOB,
	STATUS INTEGER,
	CP_TIME TIMESTAMP,
	CONSTRAINT ATTACHMENT_PK PRIMARY KEY (ATT_ID)
);
CREATE UNIQUE INDEX IF NOT EXISTS ATTACHEMENT_PK ON %SCHEMA%.ATTACHMENT (ATT_ID);
CREATE INDEX IF NOT EXISTS ATT_REQ_ID ON %SCHEMA%.ATTACHMENT (ATT_REQ_ID);
ALTER TABLE %SCHEMA%.RESPONCE ADD CONSTRAINT IF NOT EXISTS RESPONCE_FK FOREIGN KEY (RES_REQ_ID) REFERENCES %SCHEMA%.REQUEST(REQ_ID) ON DELETE CASCADE;
ALTER TABLE %SCHEMA%.ATTACHMENT ADD CONSTRAINT IF NOT EXISTS ATTACHMENT_FK FOREIGN KEY (ATT_REQ_ID) REFERENCES %SCHEMA%.REQUEST(REQ_ID) ON DELETE CASCADE;

CREATE TABLE IF NOT EXISTS %SCHEMA%.USER (
	NAME VARCHAR(60) NOT NULL,
	PASSWORD VARCHAR(250),
	TOC VARCHAR(250),
	EXPIRE_AT TIMESTAMP,
	CONSTRAINT USER_PK PRIMARY KEY (NAME)
);
CREATE UNIQUE INDEX IF NOT EXISTS USER_PR ON %SCHEMA%.USER (NAME);

CREATE TABLE IF NOT EXISTS %SCHEMA%.MESSAGE (
	ID VARCHAR(100) NOT NULL,
	URL VARCHAR(512) NOT NULL,
	"METHOD" VARCHAR(10) NOT NULL,
	HEADERS CLOB,
	BODY CLOB,
	ATTEMPT INTEGER,
	-- MAX_RETRIES INTEGER,
	RETRY_INTERVAL INTEGER,
	USER_NAME VARCHAR(100) NOT NULL,
	NEXT_RUN TIMESTAMP NOT NULL,
	LOCKED_BY VARCHAR(100),
	LOCK_TIME TIMESTAMP,
	CONSTRAINT MESSAGE_PK PRIMARY KEY (ID)
);
CREATE UNIQUE INDEX  IF NOT EXISTS PRIMARY_KEY_13 ON %SCHEMA%.MESSAGE (ID);
CREATE INDEX IF NOT EXISTS MESSAGE_LOCKEDBY ON %SCHEMA%.MESSAGE(LOCKED_BY);

CREATE TABLE IF NOT EXISTS %SCHEMA%.MESSAGE_LOG (
	MES_ID VARCHAR(100) NOT NULL,
	URL VARCHAR(512) NOT NULL,
	ATTEMPT INTEGER,
	DELIVERY_TIME TIMESTAMP,
	STATUS INTEGER,
	BODY CLOB,
	CONSTRAINT MESSAGE_LOG_PK PRIMARY KEY (MES_ID)
);
CREATE UNIQUE INDEX  IF NOT EXISTS MESSAGE_LOG_PK ON %SCHEMA%.MESSAGE_LOG (MES_ID);

CREATE TABLE IF NOT EXISTS  %SCHEMA%.HOOK (
	USER_NAME VARCHAR(100) NOT NULL,
	NAME VARCHAR(100) NOT NULL,
	HEADERS CLOB,
	METHOD VARCHAR(20) DEFAULT 'post',
	BODY CLOB,
	MAX_RETRIES INTEGER,
	RETRY_INTERVAL INTEGER,
	URL VARCHAR(512) NOT NULL,
	CONSTRAINT HOOK_PK PRIMARY KEY (USER_NAME,NAME)
);
CREATE UNIQUE INDEX IF NOT EXISTS PRIMARY_KEY_E ON  %SCHEMA%.HOOK (USER_NAME,NAME);

