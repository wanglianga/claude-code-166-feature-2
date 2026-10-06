package cn.townhc.homebed.domain.entity;

import cn.townhc.homebed.domain.enums.DomainEnums.SubstituteFollowUp;
import cn.townhc.homebed.domain.enums.DomainEnums.SubstituteStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 耗材缺货替代确认单。
 * 护士上门发现原医嘱指定材料缺货时发起，平台固化：
 * 原医嘱/护理方案 → 替代材料规格 → 医保报销差异 → 可能影响 → 医生确认 → 家属签字 → 执行 → 后续效果追踪。
 * 家属不同意则转补货任务并调整本次护理计划。
 */
@Getter
@Setter
@Entity
@Table(name = "material_substitute")
public class MaterialSubstitute extends BaseEntity {
    @Column(nullable = false, unique = true, length = 32)
    private String substituteNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    /** 发起替代的上门任务（计划阶段缺货可空） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id")
    private Visit visit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    private VisitPlan plan;

    /** 原医嘱（规定使用原品材料的医嘱，可空但展示链路优先取它） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "related_order_id")
    private MedicalOrder relatedOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proposer_id")
    private SysUser proposer;

    // ===== 原品 / 替代品 =====
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "original_material_id")
    private Material originalMaterial;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "substitute_material_id")
    private Material substituteMaterial;

    private Integer quantity = 1;

    /** 缺货情况说明（库存/配送延误） */
    @Column(length = 512)
    private String shortageNote;

    /** 替代后可能影响（固定效果、过敏风险、使用注意） */
    @Column(length = 1024)
    private String possibleImpact;

    /** 原医嘱/护理方案对该材料用法要求的快照，防止医嘱事后修改无法还原 */
    @Column(length = 1024)
    private String originalUsageSnapshot;

    /** 替代品批号（执行时登记） */
    @Column(length = 32)
    private String batchNo;

    // ===== 医保报销差异快照（申请时按目录版本固化，事后可还原） =====
    @Column(precision = 10, scale = 2)
    private BigDecimal origUnitPrice;
    @Column(length = 8)
    private String origInsuranceCategory;
    @Column(precision = 4, scale = 2)
    private BigDecimal origReimbursementRate;
    private Boolean origReimbursable = false;
    /** 原品方案预估：总费用 / 医保报销 / 家属自费 */
    @Column(precision = 12, scale = 2)
    private BigDecimal origTotalAmount;
    @Column(precision = 12, scale = 2)
    private BigDecimal origInsuranceAmount;
    @Column(precision = 12, scale = 2)
    private BigDecimal origSelfPayAmount;

    @Column(precision = 10, scale = 2)
    private BigDecimal subUnitPrice;
    @Column(length = 8)
    private String subInsuranceCategory;
    @Column(precision = 4, scale = 2)
    private BigDecimal subReimbursementRate;
    private Boolean subReimbursable = false;
    @Column(precision = 12, scale = 2)
    private BigDecimal subTotalAmount;
    @Column(precision = 12, scale = 2)
    private BigDecimal subInsuranceAmount;
    @Column(precision = 12, scale = 2)
    private BigDecimal subSelfPayAmount;

    /** 替代-原品 差额（正=增加，负=减少） */
    @Column(precision = 12, scale = 2)
    private BigDecimal totalDiff;
    @Column(precision = 12, scale = 2)
    private BigDecimal insuranceDiff;
    @Column(precision = 12, scale = 2)
    private BigDecimal selfPayDiff;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SubstituteStatus status = SubstituteStatus.PROPOSED;

    // ===== 医生确认 =====
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id")
    private SysUser doctor;
    /** 医生是否同意使用替代材料 */
    private Boolean doctorApproved;
    @Column(length = 1024)
    private String doctorComment;
    private LocalDateTime doctorConfirmedAt;

    // ===== 家属知情签字 =====
    @Column(length = 32)
    private String familySignName;
    @Column(length = 256)
    private String familySignPath;
    private LocalDateTime familySignedAt;
    /** 家属决定说明（同意时的补充意见 / 拒绝原因） */
    @Column(length = 1024)
    private String familyDecisionNote;

    // ===== 替代执行 =====
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "executed_by_id")
    private SysUser executedBy;
    private LocalDateTime executedAt;
    @Column(length = 512)
    private String executionNote;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "executed_tx_id")
    private MaterialTransaction executedTx;

    // ===== 后续效果追踪 =====
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private SubstituteFollowUp latestOutcome;
    private LocalDateTime latestOutcomeAt;

    // ===== 医生回看替代原因并调整后续护理材料 =====
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_doctor_id")
    private SysUser reviewDoctor;
    /** 回看结论（替代原因确认、过敏/愈合变慢判断） */
    @Column(length = 1024)
    private String reviewComment;
    /** 后续护理材料调整措施（停用替代/换回原品/加用其他材料/加密频次） */
    @Column(length = 1024)
    private String reviewAction;
    /** 回看时生成的护理方案调整医嘱 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_order_id")
    private MedicalOrder reviewOrder;
    private LocalDateTime reviewedAt;

    // ===== 家属拒绝后的联动任务 =====
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replenish_task_id")
    private MaterialReplenishTask replenishTask;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "care_adjustment_id")
    private CarePlanAdjustment careAdjustment;
}
