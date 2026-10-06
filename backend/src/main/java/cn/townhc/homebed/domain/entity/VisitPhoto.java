package cn.townhc.homebed.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** 巡诊现场照片：体征照片、压疮/换药照片、门景照等证据 */
@Getter
@Setter
@Entity
@Table(name = "visit_photo")
public class VisitPhoto extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "visit_id")
    private Visit visit;

    @Column(nullable = false, length = 256)
    private String filePath;

    /** 照片类型：scene/scene_home/ulcer/dressing/signature/catheter/substitute_tracking/other */
    @Column(length = 32)
    private String photoType;

    @Column(length = 256)
    private String caption;

    /** 替代材料效果追踪照片：关联替代确认单（巡诊正常照片该字段为空） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "substitute_id")
    private MaterialSubstitute substitute;
}
