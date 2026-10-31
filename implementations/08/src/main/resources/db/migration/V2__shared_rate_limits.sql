CREATE TABLE principal_rate_limits (
 principal varchar(128) PRIMARY KEY,
 minute_bucket bigint NOT NULL,
 request_count integer NOT NULL CHECK(request_count>0)
);
