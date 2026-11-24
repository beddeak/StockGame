package kr.hetagram.stock;

import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** 차입 당시의 불변 계약입니다. 관리자 설정 변경은 기존 계약과 추가 차입의 조건을 바꾸지 않습니다. */
public record LoanContract(GameConfig.LoanRules rules) {
    public LoanContract {Objects.requireNonNull(rules);}
    public static LoanContract from(GameConfig.LoanRules rules) {return new LoanContract(rules);}

    /**
     * schema 2는 선이자 포함 단순화 계약입니다. schema 1(예전 계약)의 연체료·미납·유예·페널티 항목은 새 규칙에 없어 무시하고,
     * 이미 원금을 전부 받은 계약이므로 선이자는 0으로 읽습니다.
     */
    public static LoanContract load(ConfigurationSection yaml,String path,GameConfig.LoanRules fallback) {
        if(!yaml.isSet(path))return from(fallback);
        var section=yaml.getConfigurationSection(path);GameConfig.require(section!=null,path+": 계약 설정 필요");
        int schema=GameConfig.integer(section,"schema",1,2);
        for(String key:List.of("enabled","require-low-assets","allow-top-up"))
            GameConfig.require(section.isBoolean(key),path+"."+key+": true/false 필요");
        long min=GameConfig.money(section,"min-principal",1,100000000000000L);
        long max=GameConfig.money(section,"max-principal",min,100000000000000L);
        double maxInterest=GameConfig.number(section,"max-interest-percent",0,1000);
        double upfront=schema==1?0:GameConfig.number(section,"upfront-fee-percent",0,90);
        GameConfig.require(upfront<=maxInterest,path+": 선이자가 총이자 상한보다 큽니다");
        return from(new GameConfig.LoanRules(section.getBoolean("enabled"),GameConfig.money(section,"bankruptcy-threshold",0,100000000000000L),min,max,
                GameConfig.money(section,"default-principal",min,max),GameConfig.integer(section,"term-seconds",1,31536000),
                GameConfig.integer(section,"interest-interval-seconds",1,31536000),GameConfig.number(section,"interest-percent",0,100),
                maxInterest,upfront,section.getBoolean("require-low-assets"),section.getBoolean("allow-top-up")));
    }

    public void save(ConfigurationSection yaml,String path) {
        var r=rules;yaml.set(path+".schema",2);yaml.set(path+".enabled",r.enabled());
        yaml.set(path+".bankruptcy-threshold",BigDecimal.valueOf(r.bankruptcyThreshold(),2));
        yaml.set(path+".min-principal",BigDecimal.valueOf(r.minPrincipal(),2));yaml.set(path+".max-principal",BigDecimal.valueOf(r.maxPrincipal(),2));
        yaml.set(path+".default-principal",BigDecimal.valueOf(r.defaultPrincipal(),2));
        yaml.set(path+".term-seconds",r.termSeconds());yaml.set(path+".interest-interval-seconds",r.interestIntervalSeconds());
        yaml.set(path+".interest-percent",r.interestPercent());yaml.set(path+".max-interest-percent",r.maxInterestPercent());
        yaml.set(path+".upfront-fee-percent",r.upfrontFeePercent());
        yaml.set(path+".require-low-assets",r.requireLowAssets());yaml.set(path+".allow-top-up",r.allowTopUp());
    }
}
