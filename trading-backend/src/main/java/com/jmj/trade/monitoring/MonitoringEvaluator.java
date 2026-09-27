package com.jmj.trade.monitoring;

import tools.jackson.databind.JsonNode;

interface MonitoringEvaluator {

    JsonNode evaluate(MonitoringEvaluationContract.Request request);
}
