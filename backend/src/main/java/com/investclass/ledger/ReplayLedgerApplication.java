package com.investclass.ledger;

import com.investclass.ledger.core.AccountingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 投资教学平台可回放核算链后端。
 * 事件账本(business_event)是唯一事实源；投影/快照皆为可重建的派生物。
 */
@SpringBootApplication
@EnableConfigurationProperties(AccountingProperties.class)
public class ReplayLedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReplayLedgerApplication.class, args);
    }
}
