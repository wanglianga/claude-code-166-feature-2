package cn.townhc.homebed.domain.entity;

import cn.townhc.homebed.domain.enums.DomainEnums.RestockStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 耗材补货任务。来源：家属拒绝替代、缺货异常、低库存预警；
 * 到货入库后自动（或经确认）安排原品补换药上门并恢复/调整护理计划。
 */
@Getter
@Setter
@Entity
@Table(name = "restock_task")
public class RestockTask extends BaseEntity {
    @Column(nullable = false, unique = true, length = 32)
    private String taskNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id")
    private Material material;

    /** 需求数量（含安全库存补足） */
    private Integer requiredQty = 1;

    /** 当前库存快照 */
    private Integer currentStockSnapshot;

    /** 紧急程度：普通/加急（家属拒绝替代等待原品补换时为加急） */
    private Boolean urgent = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RestockStatus status = RestockStatus.PENDING;

    /** 来源说明：家属拒绝替代单号 / 缺货异常 / 看板预警等 */
    @Column(length = 512)
    private String sourceNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_id")
    private SysUser reporter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "handler_id")
    private SysUser handler;

    @Column(length = 512)
    private String handleNote;
    private LocalDateTime orderedAt;
    private LocalDateTime arrivedAt;

    /** 到货入库流水号 */
    @Column(length = 32)
    private String stockInTxnNo;

    /** 到货后生成的补换药巡诊任务号（闭环依据） */
    @Column(length = 32)
    private String makeupVisitNo;
}
