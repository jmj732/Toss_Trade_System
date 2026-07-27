package com.jmj.trade.broker.connection;

import java.util.UUID;

record ValidationTarget(UUID connectionId, long credentialRevision) {
}
