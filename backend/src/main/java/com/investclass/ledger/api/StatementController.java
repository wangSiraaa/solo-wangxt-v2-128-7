package com.investclass.ledger.api;

import com.investclass.ledger.eod.EodService;
import com.investclass.ledger.eod.ExternalStatementRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 录入外部对账单（账实核对用；绝不反写事件或快照历史）。 */
@RestController
public class StatementController {

    private final ExternalStatementRepository statements;

    public StatementController(ExternalStatementRepository statements) {
        this.statements = statements;
    }

    public record StatementRequest(String accountId, LocalDate businessDate,
                                   String instrument, BigDecimal externalQty,
                                   BigDecimal externalFractionalQty, BigDecimal externalCash,
                                   BigDecimal externalCost) {
    }

    @PostMapping("/api/statements")
    public String upload(@RequestBody StatementRequest req) {
        statements.upsert(req.accountId(), req.businessDate(), req.instrument(),
                req.externalQty(), req.externalFractionalQty(),
                req.externalCash(), req.externalCost());
        return "OK";
    }
}
