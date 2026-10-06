package cn.townhc.homebed.domain.entity;

import cn.townhc.homebed.domain.enums.DomainEnums.RiskEventStatus;
import cn.townhc.homebed.domain.enums.DomainEnums.RiskSeverity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 风险/不良事件：病情恶化、压疮新发、导尿异常、冷链断链、上门超时等 */
@Getter
@Setter
@Entity
@Table(name = "risk_event")
public class RiskEvent extends BaseEntity {
    @Column(nullable = false, unique = true, length = 32)
    private String riskNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id")
    private Visit visit;

    /** 若风险由替代耗材效果引起（愈合变慢/过敏），关联替代确认单，供医生回看替代原因 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "substitution_id")
    private MaterialSubstitution substitution;

    /** 风险类型：病情恶化/生命体征异常/压疮加重/导尿感染风险/冷链异常/照护缺失/其他 */
    @Column(nullable = false, length = 32)
    private String riskType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RiskSeverity severity;

    @Column(nullable = false, length = 1024)
    private String description;

    @Column(length = 256)
    private String evidencePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RiskEventStatus status = RiskEventStatus.OPEN;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_id")
    private SysUser reporter;

    private LocalDateTime reportedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confirmer_id")
    private SysUser confirmer;

    @Column(length = 1024)
    private String handlePlan;

    @Column(length = 1024)
    private String closeNote;

    private LocalDateTime closedAt;
}
