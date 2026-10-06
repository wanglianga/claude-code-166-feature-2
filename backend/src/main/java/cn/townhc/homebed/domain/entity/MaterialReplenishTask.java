package cn.townhc.homebed.domain.entity;

import cn.townhc.homebed.domain.enums.DomainEnums.ReplenishStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 耗材补货任务。
 * 家属不同意替代、或缺货预警触发时生成；到货入库后闭环，
 * 与“本次护理计划调整”联动，保证原医嘱材料补做可追溯。
 */
@Getter
@Setter
@Entity
@Table(name = "material_replenish_task")
public class MaterialReplenishTask extends BaseEntity {
    @Column(nullable = false, unique = true, length = 32)
    private String taskNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id")
    private Material material;

    /** 需求/申请采购数量 */
    private Integer requiredQty = 1;

    /** 触发原因：家属拒绝替代 / 缺货预警 / 异常上报 */
    @Column(length = 512)
    private String reason;

    /** 关联的替代确认单（拒绝替代时） */
    @OneToOne(mappedBy = "replenishTask", fetch = FetchType.LAZY)
    @JsonIgnore
    private MaterialSubstitute substitute;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReplenishStatus status = ReplenishStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creator_id")
    private SysUser creator;

    /** 下单/催货说明 */
    @Column(length = 512)
    private String orderNote;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ordered_by_id")
    private SysUser orderedBy;
    private LocalDateTime orderedAt;

    /** 预计到货 */
    private LocalDateTime expectedArrivalAt;

    /** 到货入库流水 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "arrival_tx_id")
    private MaterialTransaction arrivalTx;
    private LocalDateTime arrivedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closer_id")
    private SysUser closer;
    @Column(length = 512)
    private String closeNote;
}
