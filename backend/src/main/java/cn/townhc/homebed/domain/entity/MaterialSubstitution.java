package cn.townhc.homebed.domain.entity;

import cn.townhc.homebed.domain.enums.DomainEnums.SubstituteStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 耗材缺货替代确认单。
 * 流程：护士上门发现医嘱指定敷料库存不足 → 发起替代（系统测算规格/医保报销差异/可能影响）
 *   → 医生确认 → 家属知情签字（同意→按替代材料执行并扣库存；不同意→生成补货任务并调整本次护理计划）
 *   → 执行后通过后续伤口照片持续追踪，愈合变慢/过敏时医生可回看替代原因并调整后续护理材料。
 */
@Getter
@Setter
@Entity
@Table(name = "material_substitution")
public class MaterialSubstitution extends BaseEntity {
    @Column(nullable = false, unique = true, length = 32)
    private String substitutionNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "visit_id")
    private Visit visit;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    private VisitPlan plan;

    /** 发起替代的护士 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proposer_id")
    private SysUser proposer;

    /** 医嘱指定（原定）耗材：库存不足 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "original_material_id")
    private Material originalMaterial;

    /** 原医嘱（指定敷料来源，可空） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "medical_order_id")
    private MedicalOrder medicalOrder;

    /** 护士提出的替代耗材 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "substitute_material_id")
    private Material substituteMaterial;

    /** 替代数量 */
    private Integer quantity = 1;

    /** 原医嘱/护理要求摘要（换药物品与频次快照） */
    @Column(length = 1024)
    private String originalOrderSnapshot;

    /** 缺货情况说明（发起时库存等） */
    @Column(length = 512)
    private String shortageReason;

    // ===== 医保报销差异（发起时测算快照，事后可还原） =====
    /** 原品医保类别：甲类/乙类/丙类 */
    @Column(length = 8)
    private String originalInsuranceCategory;
    @Column(length = 8)
    private String substituteInsuranceCategory;
    /** 原品报销比例 */
    @Column(precision = 4, scale = 2)
    private BigDecimal originalReimbRate;
    @Column(precision = 4, scale = 2)
    private BigDecimal substituteReimbRate;
    /** 原品单价（收费目录价，无目录取采购价） */
    @Column(precision = 10, scale = 2)
    private BigDecimal originalUnitPrice;
    @Column(precision = 10, scale = 2)
    private BigDecimal substituteUnitPrice;
    /** 原品医保报销额/自费额 */
    @Column(precision = 10, scale = 2)
    private BigDecimal originalInsuranceAmount;
    @Column(precision = 10, scale = 2)
    private BigDecimal originalSelfAmount;
    /** 替代品医保报销额/自费额 */
    @Column(precision = 10, scale = 2)
    private BigDecimal substituteInsuranceAmount;
    @Column(precision = 10, scale = 2)
    private BigDecimal substituteSelfAmount;
    /** 报销差异（替代品报销额 - 原品报销额，负数表示报销减少） */
    @Column(precision = 10, scale = 2)
    private BigDecimal insuranceDiff;
    /** 自费差异（替代品自费额 - 原品自费额，正数表示家属多掏） */
    @Column(precision = 10, scale = 2)
    private BigDecimal selfPayDiff;

    /** 可能影响（黏贴/吸收/过敏风险/更换频次等，系统模板+护士补充） */
    @Column(length = 1024)
    private String possibleImpact;

    // ===== 医生确认 =====
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SubstituteStatus status = SubstituteStatus.PROPOSED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id")
    private SysUser doctor;

    @Column(length = 1024)
    private String doctorComment;

    private LocalDateTime doctorConfirmedAt;

    // ===== 家属知情签字 =====
    @Column(length = 32)
    private String familySignName;
    @Column(length = 256)
    private String familySignPath;
    private LocalDateTime familySignedAt;
    /** 家属意见（同意/不同意及原因） */
    @Column(length = 512)
    private String familyComment;

    // ===== 执行 =====
    private LocalDateTime executedAt;
    /** 替代耗材出库流水（执行时扣库存生成） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "out_txn_id")
    private MaterialTransaction outTransaction;

    // ===== 家属拒绝后的护理计划调整与补货 =====
    /** 本次护理计划调整说明（如改为生理盐水冲洗+无菌纱布覆盖，待原品到货补换） */
    @Column(length = 1024)
    private String planAdjustment;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restock_task_id")
    private RestockTask restockTask;

    /** 随访发现过敏/愈合变慢后，医生对后续护理材料的调整意见（同时下护理方案调整医嘱） */
    @Column(length = 1024)
    private String materialAdjustNote;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "adjust_order_id")
    private MedicalOrder adjustOrder;
    private LocalDateTime materialAdjustedAt;
}
