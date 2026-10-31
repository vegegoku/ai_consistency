CREATE TABLE request_rates(principal varchar(200) NOT NULL,minute bigint NOT NULL,requests integer NOT NULL CHECK(requests>0),PRIMARY KEY(principal,minute));
