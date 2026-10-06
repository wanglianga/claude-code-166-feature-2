#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""耗材缺货替代确认功能端到端冒烟：真实 HTTP 调用 8080。"""
import json, sys, urllib.request, urllib.error

BASE = "http://localhost:8080/api"
passed, failed = [], []

def call(method, path, token=None, body=None):
    req = urllib.request.Request(BASE + path, method=method)
    if token: req.add_header("Authorization", "Bearer " + token)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data=data, timeout=15) as r:
            payload = json.load(r)
    except urllib.error.HTTPError as e:
        payload = json.load(e)
    if payload.get("code") != 0:
        raise AssertionError(f"{method} {path} -> code={payload.get('code')} msg={payload.get('message')}")
    return payload["data"]

def check(name, cond, extra=""):
    (passed if cond else failed).append(name)
    print(("  ✓ " if cond else "  ✗ ") + name + (f"  {extra}" if cond and extra else "")
          + ("" if cond else f"  FAIL:{extra}"))

def login(u):
    return call("POST", "/auth/login", body={"username": u, "password": "123456"})["token"]

tok_n = login("nurse1")
tok_d = login("doctor")
tok_d2 = login("doctor2")
tok_f = login("family")
tok_a = login("admin")
check("1. 四角色登录(nurse/doctor/doctor2/family/admin)", all([tok_n, tok_d, tok_d2, tok_f, tok_a]))

# 列表
subs = call("GET", "/substitutes", tok_n)
by_no = {s["substituteNo"]: s for s in subs}
check("2. 预置5张替代确认单", len(subs) >= 5, f"{len(subs)}张")
s1 = by_no["TD20260929001"]; s2 = by_no["TD20261002002"]
s3 = by_no["TD20261005003"]; s4 = by_no["TD20261005004"]; s5 = by_no["TD20261005005"]
check("2.1 S1已执行追踪中", s1["status"] == "EXECUTED")
check("2.2 S2医生已回看调整", s2["status"] == "DOCTOR_REVIEWED")
check("2.3 S3待医生确认", s3["status"] == "PROPOSED")
check("2.4 S4待家属签字", s4["status"] == "DOCTOR_CONFIRMED")
check("2.5 S5家属拒绝", s5["status"] == "FAMILY_REJECTED")

# S1 详情：原医嘱/医保差异/追踪照片
d1 = call("GET", f"/substitutes/{s1['id']}", tok_f)
check("3. S1固化原医嘱快照", "藻酸盐" in d1["substitute"]["originalUsageSnapshot"])
check("3.1 S1医保差异(水胶体甲类更省)", float(d1["substitute"]["selfPayDiff"]) == -9.40,
      f"自费差={d1['substitute']['selfPayDiff']}")
check("3.2 S1有医生确认+家属签字", d1["substitute"]["doctorApproved"] is True
      and d1["substitute"]["familySignPath"])
check("3.3 S1追踪记录≥1且含照片", len(d1["trackings"]) >= 1 and len(d1["photos"]) >= 2,
      f"track={len(d1['trackings'])} photos={len(d1['photos'])}")

# S2：过敏+变慢+回看医嘱
d2 = call("GET", f"/substitutes/{s2['id']}", tok_d)
outs = [t["outcome"] for t in d2["trackings"]]
check("4. S2追踪含变慢+过敏", "SLOW" in outs and "ALLERGY" in outs, str(outs))
check("4.1 S2回看结论与调整措施", bool(d2["substitute"]["reviewAction"])
      and d2["substitute"]["reviewDoctor"]["name"] == "周敏")
check("4.2 S2已生成护理调整医嘱", d2["reviewOrder"] is not None
      and "停用" in d2["reviewOrder"]["content"])
# 过敏自动风险事件
risks = call("GET", "/risks?status=OPEN", tok_a)
check("4.3 过敏自动生成HIGH风险事件",
      any(r["riskType"] == "替代材料过敏反应" and r["severity"] == "HIGH" for r in risks))

# ===== 正向流程：S3 医生确认 =====
if s3["status"] == "PROPOSED":
    call("POST", f"/substitutes/{s3['id']}/doctor-confirm", tok_d,
         {"approved": True, "comment": "冒烟测试：同意泡沫敷料应急，注意患者曾过敏需观察"})
    s3b = call("GET", f"/substitutes/{s3['id']}", tok_n)["substitute"]
    check("5. S3医生确认后待家属签字", s3b["status"] == "DOCTOR_CONFIRMED")
    # 权限：护士不能医生确认
    try:
        call("POST", f"/substitutes/{s4['id']}/doctor-confirm", tok_n, {"approved": True, "comment": "x"})
        check("5.1 护士不可医生确认", False, "未拦截")
    except AssertionError as e:
        check("5.1 护士不可医生确认(403)", "403" in str(e))
else:
    check("5. S3医生确认后待家属签字(重跑跳过)", True, f"当前{s3['status']}")
    check("5.1 护士不可医生确认(重跑跳过)", True)

# ===== 新发起一张替代单走完整同意→执行→追踪→回看 =====
patients = call("GET", "/patients", tok_n)
p2id = next(p["id"] for p in patients if p["name"] == "李长顺")
visits = call("GET", "/visits?status=SCHEDULED", tok_n)
v = next(x for x in visits if x["patient"]["id"] == p2id)
mats = call("GET", "/materials", tok_n)
orig = next(m for m in mats if m["code"] == "HC004")
sub_m = next(m for m in mats if m["code"] == "HC006")
new_id = call("POST", "/substitutes", tok_n, {
    "visitId": v["id"], "originalMaterialId": orig["id"],
    "substituteMaterialId": sub_m["id"], "quantity": 1,
    "shortageNote": "冒烟：配送延误",
    "possibleImpact": "水胶体需观察黏胶过敏"})["id"]
nd = call("GET", f"/substitutes/{new_id}", tok_n)["substitute"]
check("6. 护士发起替代自动固化医保差异",
      nd["origInsuranceCategory"] == "乙类" and nd["subInsuranceCategory"] == "甲类"
      and float(nd["totalDiff"]) == -10.00, f"totalDiff={nd['totalDiff']}")
# 库存充足不能发起
try:
    call("POST", "/substitutes", tok_n, {
        "visitId": v["id"], "originalMaterialId": sub_m["id"],
        "substituteMaterialId": orig["id"], "quantity": 1})
    check("6.1 原品有货时禁止发起替代", False, "未拦截")
except AssertionError as e:
    check("6.1 原品有货时禁止发起替代", "尚不构成缺货" in str(e))
# 医生确认 → 家属同意
call("POST", f"/substitutes/{new_id}/doctor-confirm", tok_d, {"approved": True, "comment": "同意"})
# 未签字不能执行（用护士尝试执行）
try:
    call("POST", f"/substitutes/{new_id}/execute", tok_n, {"batchNo": "B1"})
    check("6.2 家属未签字禁止执行", False, "未拦截")
except AssertionError as e:
    check("6.2 家属未签字禁止执行", "家属签字" in str(e) or "FAMILY_AGREED" in str(e))
# 家属签字同意（复用已有签字图片路径）
sign = d1["substitute"]["familySignPath"]
call("POST", f"/substitutes/{new_id}/family-decide", tok_f,
     {"agreed": True, "signName": "刘桂兰", "signPath": sign, "note": "冒烟同意"})
stock_before = sub_m["stockQty"]
call("POST", f"/substitutes/{new_id}/execute", tok_n,
     {"batchNo": "SMOKE001", "note": "冒烟执行", "photoPaths": [sign]})
mat_after = next(m for m in call("GET", "/materials", tok_n) if m["id"] == sub_m["id"])
nd2 = call("GET", f"/substitutes/{new_id}", tok_n)["substitute"]
check("6.3 执行后替代品扣库存", mat_after["stockQty"] == stock_before - 1,
      f"{stock_before}->{mat_after['stockQty']}")
check("6.4 执行后状态追踪中+有出库流水", nd2["status"] == "EXECUTED" and nd2["executedTx"]["id"])
# 追踪：愈合变慢 → 风险事件
t = call("POST", f"/substitutes/{new_id}/tracking", tok_n,
         {"outcome": "SLOW", "note": "冒烟：愈合变慢观察", "photoPaths": [sign], "visitId": v["id"]})
check("6.5 追踪登记成功", t["outcome"] == "SLOW")
risks2 = call("GET", "/risks?status=OPEN", tok_a)
check("6.6 愈合变慢自动MEDIUM风险",
      any(r["riskType"] == "替代后伤口愈合变慢" for r in risks2))
# 医生回看并下医嘱
call("POST", f"/substitutes/{new_id}/doctor-review", tok_d2,
     {"comment": "冒烟回看：替代原因成立", "action": "藻酸盐到货后换回原品，加密随访", "createOrder": True})
nd3 = call("GET", f"/substitutes/{new_id}", tok_n)["substitute"]
check("6.7 医生回看完成+调整医嘱", nd3["status"] == "DOCTOR_REVIEWED" and nd3["reviewOrder"]["id"])

# ===== 拒绝分支：S4 家属不同意 → 补货+计划调整 =====
call("POST", f"/substitutes/{s4['id']}/family-decide", tok_f,
     {"agreed": False, "signName": "刘桂兰", "signPath": sign, "note": "冒烟：坚持用原品"})
d4 = call("GET", f"/substitutes/{s4['id']}", tok_f)
check("7. S4拒绝后生成补货任务", d4["replenishTask"] is not None
      and d4["replenishTask"]["status"] == "PENDING")
check("7.1 S4拒绝后生成护理计划调整", d4["careAdjustment"] is not None
      and "补做" in d4["careAdjustment"]["adjustmentContent"])
rt_id = d4["replenishTask"]["id"]; ca_id = d4["careAdjustment"]["id"]
orig4 = d4["substitute"]["originalMaterial"]
# 下单 → 到货入库
call("POST", f"/substitutes/replenish/{rt_id}/order", tok_a,
     {"note": "冒烟下单", "expectedArrivalAt": "2026-10-07T10:00:00"})
m0 = next(m for m in call("GET", "/materials", tok_n) if m["id"] == orig4["id"])
call("POST", f"/substitutes/replenish/{rt_id}/arrive", tok_n,
     {"quantity": 5, "batchNo": "ARR001", "expiryDate": "2027-10-01", "note": "冒烟到货"})
m1 = next(m for m in call("GET", "/materials", tok_n) if m["id"] == orig4["id"])
check("7.2 到货入库增加原品库存", m1["stockQty"] == m0["stockQty"] + 5, f"{m0['stockQty']}->{m1['stockQty']}")
# 安排补做 → 补做完成联动关闭补货
call("POST", f"/substitutes/adjustments/{ca_id}/schedule", tok_n,
     {"expectedMakeupAt": "2026-10-07T09:00:00", "content": "原品到货后上门补做"})
call("POST", f"/substitutes/adjustments/{ca_id}/complete", tok_n,
     {"note": "冒烟：已用原品补做"})
d4b = call("GET", f"/substitutes/{s4['id']}", tok_a)
check("7.3 补做完成且补货任务联动闭环",
      d4b["careAdjustment"]["status"] == "DONE"
      and d4b["replenishTask"]["status"] == "CLOSED",
      f"adj={d4b['careAdjustment']['status']} rep={d4b['replenishTask']['status']}")

# ===== S5 预置拒绝单：补货列表/护理调整列表可查 =====
rep = call("GET", "/substitutes/replenish/list", tok_a)
check("8. 补货任务列表含S5", any(r["taskNo"] == "BH20261005001" for r in rep))
adjs = call("GET", "/substitutes/adjustments/list", tok_n)
check("8.1 护理调整列表含S5", any(a["adjustNo"] == "HL20261005001" for a in adjs))

# ===== 合规初审 R8 =====
bills = call("GET", "/bills", tok_a)
bill = bills[0]
comp_rec = call("GET", f"/compliance/bill/{bill['id']}", tok_f)
comp_detail = call("GET", f"/compliance/{comp_rec['id']}", tok_f)
r8 = [i for i in comp_detail["issues"] if i["ruleCode"] == "R8"]
bill_detail = call("GET", f"/bills/{bill['id']}", tok_f)
check("9. 账单合规初审命中R8替代规则", len(r8) >= 1, f"{len(r8)}条")
check("9.1 R8含医保差异与医生/家属信息", r8 and "藻酸盐" in r8[0]["message"]
      and "签字" in r8[0]["message"])
# 替代出库行账单备注
sub_item = [i for i in bill_detail["items"] if i.get("note") and "缺货替代" in i["note"]]
check("9.2 账单明细行标注缺货替代", len(sub_item) >= 1, f"{len(sub_item)}行")

# ===== 全链追溯含替代链路 =====
apps = call("GET", "/applications", tok_f)
app2 = next(a for a in apps if a["patient"]["id"] == p2id)
trace = call("GET", f"/trace/application/{app2['id']}", tok_f)
subs_trace = trace["substitutes"]
stages = [t["stage"] for t in trace["timeline"]]
check("10. 全链追溯含替代确认单", len(subs_trace) >= 4, f"{len(subs_trace)}张")
check("10.1 时间线含替代/追踪/补货/计划调整节点",
      "耗材替代" in stages and "替代追踪" in stages and "补货任务" in stages and "护理计划调整" in stages,
      str({s: stages.count(s) for s in set(stages)}))

# ===== 巡诊详情携带替代单 =====
vd = call("GET", f"/visits/{s1['visit']['id']}", tok_n)
check("11. 巡诊详情携带替代确认单", len(vd["substitutes"]) >= 1)

print(f"\n==== 通过 {len(passed)} / 失败 {len(failed)} ====")
if failed:
    print("FAILED:", *failed, sep="\n - ")
    sys.exit(1)
print("ALL_SMOKE_PASSED")
