package cn.townhc.homebed.domain.entity;

import cn.townhc.homebed.domain.enums.DomainEnums.CareAdjustStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 护理计划调整记录（家属不同意替代材料时联动生成）。
 * 记录“本次护理原计划 → 调整措施 → 到货后补做安排”，并跟踪补做完成，
 * 保证患者换药需求不被遗漏、护理计划调整有据可查。
 */
@Getter
@Setter
@Entity
@Table(name = "care_plan_adjustment")
public class CarePlanAdjustment extends BaseEntity {
    @Column(nullable = false, unique = true, length = 32)
    private String adjustNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id")
    private Visit visit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    private VisitPlan plan;

    /** 关联替代确认单 */
    @OneToOne(mappedBy = "careAdjustment", fetch = FetchType.LAZY)
    @JsonIgnore
    private MaterialSubstitute substitute;

    /** 调整类型：本次暂缓换药 / 改用基础护理 / 延期补做 等 */
    @Column(length = 32)
    private String adjustKind;

    /** 本次护理原计划快照 */
    @Column(length = 1024)
    private String originalPlanSnapshot;

    /** 调整措施（本次现场处理方式） */
    @Column(length = 1024)
    private String adjustmentContent;

    /** 原品到货后补做安排 */
    private LocalDateTime expectedMakeupAt;

    /** 实际补做巡诊 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "makeup_visit_id")
    private Visit makeupVisit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CareAdjustStatus status = CareAdjustStatus.PROPOSED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "maker_id")
    private SysUser maker;

    /** 补做完成说明 */
    @Column(length = 1024)
    private String completionNote;
    private LocalDateTime completedAt;
}
