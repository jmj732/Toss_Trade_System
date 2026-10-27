package com.jmj.trade.investment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PositionMandatePolicyTest {

    @Test
    void tacticalPriceBreachRequiresExitEvaluationWithoutCreatingAnOrder() {
        assertThat(PositionMandatePolicy.resolve("TACTICAL", "PRICE_RISK_BREACH", "INTACT", false))
                .isEqualTo(PositionMandatePolicy.Response.EXIT_EVALUATION);
    }

    @Test
    void strategicPriceBreachWithIntactThesisRequiresRevalidation() {
        assertThat(PositionMandatePolicy.resolve("STRATEGIC", "PRICE_RISK_BREACH", "INTACT", false))
                .isEqualTo(PositionMandatePolicy.Response.THESIS_REVALIDATION);
    }

    @Test
    void strategicPriceBreachWithImpairedThesisRequiresExitEvaluation() {
        assertThat(PositionMandatePolicy.resolve("STRATEGIC", "INTRADAY_BREACH", "IMPAIRED", false))
                .isEqualTo(PositionMandatePolicy.Response.EXIT_EVALUATION);
    }

    @Test
    void unknownMandateDoesNotTurnPriceBreachIntoAnAutomaticSell() {
        assertThat(PositionMandatePolicy.resolve("UNKNOWN", "PRICE_RISK_BREACH", "UNKNOWN", false))
                .isEqualTo(PositionMandatePolicy.Response.MANDATE_REVIEW);
    }

    @Test
    void hardRiskRequiresUrgentReviewRegardlessOfHorizonOrPriceState() {
        assertThat(PositionMandatePolicy.resolve("STRATEGIC", "CLEAR", "INTACT", true))
                .isEqualTo(PositionMandatePolicy.Response.URGENT_REVIEW);
        assertThat(PositionMandatePolicy.resolve("TACTICAL", "NEAR", "INTACT", true))
                .isEqualTo(PositionMandatePolicy.Response.URGENT_REVIEW);
    }

    @Test
    void nonBreachDoesNotTriggerPriceRiskAction() {
        assertThat(PositionMandatePolicy.resolve("STRATEGIC", "RECOVERED", "INTACT", false))
                .isEqualTo(PositionMandatePolicy.Response.NO_PRICE_ACTION);
    }

    @Test
    void invalidatedThesisRequiresUrgentReviewEvenWithoutPriceBreach() {
        assertThat(PositionMandatePolicy.resolve("STRATEGIC", "CLEAR", "INVALIDATED", false))
                .isEqualTo(PositionMandatePolicy.Response.URGENT_REVIEW);
    }
}
