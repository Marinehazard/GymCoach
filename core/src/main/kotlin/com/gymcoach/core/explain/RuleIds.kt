// core/src/main/kotlin/com/gymcoach/core/explain/RuleIds.kt
package com.gymcoach.core.explain

/** Every R3 §10 ID as a constant so that typos fail at compile time; RuleCoverageTest checks usage. */
object RuleIds {
    const val POL_01 = "POL-01"; const val POL_02 = "POL-02"; const val POL_03 = "POL-03"
    const val DEF_01 = "DEF-01"; const val DEF_02 = "DEF-02"; const val DEF_03 = "DEF-03"; const val DEF_04 = "DEF-04"
    const val DEF_05 = "DEF-05"; const val DEF_06 = "DEF-06"; const val DEF_07 = "DEF-07"
    const val VOL_01 = "VOL-01"; const val VOL_02 = "VOL-02"; const val VOL_03 = "VOL-03"; const val VOL_04 = "VOL-04"
    const val VOL_05 = "VOL-05"; const val VOL_06 = "VOL-06"; const val VOL_07 = "VOL-07"; const val VOL_08 = "VOL-08"; const val VOL_09 = "VOL-09"
    const val FRQ_01 = "FRQ-01"; const val FRQ_02 = "FRQ-02"; const val FRQ_03 = "FRQ-03"
    const val LOAD_01 = "LOAD-01"; const val LOAD_02 = "LOAD-02"; const val LOAD_03 = "LOAD-03"; const val LOAD_04 = "LOAD-04"
    const val RIR_01 = "RIR-01"; const val RIR_02 = "RIR-02"; const val RIR_03 = "RIR-03"; const val RIR_04 = "RIR-04"; const val RIR_05 = "RIR-05"
    const val REST_01 = "REST-01"; const val REST_02 = "REST-02"; const val REST_03 = "REST-03"; const val REST_04 = "REST-04"
    const val ORD_01 = "ORD-01"; const val ORD_02 = "ORD-02"; const val ORD_03 = "ORD-03"
    const val MIX_01 = "MIX-01"; const val MIX_02 = "MIX-02"; const val MIX_03 = "MIX-03"
    const val UNI_01 = "UNI-01"; const val UNI_02 = "UNI-02"
    const val TEMPO_01 = "TEMPO-01"; const val TEMPO_02 = "TEMPO-02"
    const val ROM_01 = "ROM-01"; const val ROM_02 = "ROM-02"; const val ROM_03 = "ROM-03"
    const val SESS_01 = "SESS-01"; const val SESS_02 = "SESS-02"; const val SPLIT_01 = "SPLIT-01"; const val SPLIT_02 = "SPLIT-02"
    const val PROG_01 = "PROG-01"; const val PROG_02 = "PROG-02"; const val PROG_03 = "PROG-03"; const val PROG_04 = "PROG-04"; const val PROG_05 = "PROG-05"
    const val PLAT_01 = "PLAT-01"; const val PLAT_02 = "PLAT-02"; const val PLAT_03 = "PLAT-03"
    const val DLD_01 = "DLD-01"; const val DLD_02 = "DLD-02"; const val DLD_03 = "DLD-03"; const val DLD_04 = "DLD-04"
    const val E1RM_01 = "E1RM-01"; const val E1RM_02 = "E1RM-02"; const val E1RM_03 = "E1RM-03"; const val E1RM_04 = "E1RM-04"
    const val RDY_01 = "RDY-01"; const val RDY_02 = "RDY-02"; const val RDY_03 = "RDY-03"; const val RDY_04 = "RDY-04"; const val RDY_05 = "RDY-05"; const val RDY_06 = "RDY-06"
    const val CHK_01 = "CHK-01"
    const val RED_01 = "RED-01"; const val RED_02 = "RED-02"; const val RED_03 = "RED-03"
    const val WU_01 = "WU-01"; const val WU_02 = "WU-02"; const val WU_03 = "WU-03"; const val WU_04 = "WU-04"; const val WU_05 = "WU-05"; const val WU_06 = "WU-06"
    const val MOB_01 = "MOB-01"; const val MOB_02 = "MOB-02"; const val MOB_03 = "MOB-03"; const val MOB_04 = "MOB-04"; const val MOB_05 = "MOB-05"; const val MOB_06 = "MOB-06"; const val MOB_07 = "MOB-07"
    const val CAR_01 = "CAR-01"; const val CAR_02 = "CAR-02"; const val CAR_03 = "CAR-03"; const val CAR_04 = "CAR-04"; const val CAR_05 = "CAR-05"
    const val CAR_06 = "CAR-06"; const val CAR_07 = "CAR-07"; const val CAR_08 = "CAR-08"; const val CAR_09 = "CAR-09"
    const val NUT_01 = "NUT-01"; const val NUT_02 = "NUT-02"; const val NUT_03 = "NUT-03"; const val NUT_04 = "NUT-04"; const val NUT_05 = "NUT-05"
    const val NUT_06 = "NUT-06"; const val NUT_07 = "NUT-07"; const val NUT_08 = "NUT-08"; const val NUT_09 = "NUT-09"; const val NUT_10 = "NUT-10"
    const val NUT_11 = "NUT-11"; const val NUT_12 = "NUT-12"; const val NUT_13 = "NUT-13"; const val NUT_14 = "NUT-14"; const val NUT_15 = "NUT-15"
    const val NUT_16 = "NUT-16"; const val NUT_17 = "NUT-17"; const val NUT_18 = "NUT-18"
    const val SUP_01 = "SUP-01"; const val SUP_02 = "SUP-02"; const val SUP_03 = "SUP-03"; const val SUP_04 = "SUP-04"; const val SUP_05 = "SUP-05"
    const val SUP_06 = "SUP-06"; const val SUP_07 = "SUP-07"; const val SUP_08 = "SUP-08"; const val SUP_09 = "SUP-09"; const val SUP_10 = "SUP-10"
    const val SUP_11 = "SUP-11"; const val SUP_12 = "SUP-12"
    const val SLP_01 = "SLP-01"; const val SLP_02 = "SLP-02"; const val SLP_03 = "SLP-03"
    const val STP_01 = "STP-01"; const val STP_02 = "STP-02"; const val STR_01 = "STR-01"; const val STR_02 = "STR-02"
    const val AGE_01 = "AGE-01"; const val AGE_02 = "AGE-02"; const val AGE_03 = "AGE-03"
    const val LAY_01 = "LAY-01"; const val LAY_02 = "LAY-02"; const val LAY_03 = "LAY-03"
    const val TRV_01 = "TRV-01"; const val TRV_02 = "TRV-02"; const val TRV_03 = "TRV-03"; const val TRV_04 = "TRV-04"
    const val TIME_01 = "TIME-01"; const val TIME_02 = "TIME-02"; const val TIME_03 = "TIME-03"
    const val INJ_00 = "INJ-00"; const val INJ_01 = "INJ-01"; const val INJ_02 = "INJ-02"; const val INJ_03 = "INJ-03"; const val INJ_04 = "INJ-04"
    // Planner conventions (not R3): documented in rules.json with confidence "weak".
    const val SEL = "SEL"; const val SUB = "SUB"; const val REBAL = "REBAL"; const val TIER = "TIER"
    const val R4_2_6 = "R4-2.6"; const val R4_4_5 = "R4-4.5"; const val R4_5_4 = "R4-5.4"; const val R4_5_5 = "R4-5.5"
    const val RIR_CAP = "RIR-CAP"; const val ROUNDING = "ROUNDING"; const val OVERRIDE = "OVERRIDE"
    const val TIME_L1 = "TIME-L1"; const val TIME_L2 = "TIME-L2"; const val TIME_L3 = "TIME-L3"; const val TIME_L4 = "TIME-L4"
    const val TIME_L5 = "TIME-L5"; const val TIME_L6 = "TIME-L6"; const val TIME_L7 = "TIME-L7"

    /** Every constant above, for RuleCoverageTest / KnowledgeValidationTest ("every RuleIds value has a rules.json entry"). */
    val ALL: List<String> = listOf(
        POL_01, POL_02, POL_03, DEF_01, DEF_02, DEF_03, DEF_04, DEF_05, DEF_06, DEF_07,
        VOL_01, VOL_02, VOL_03, VOL_04, VOL_05, VOL_06, VOL_07, VOL_08, VOL_09, FRQ_01, FRQ_02, FRQ_03,
        LOAD_01, LOAD_02, LOAD_03, LOAD_04, RIR_01, RIR_02, RIR_03, RIR_04, RIR_05, REST_01, REST_02, REST_03, REST_04,
        ORD_01, ORD_02, ORD_03, MIX_01, MIX_02, MIX_03, UNI_01, UNI_02, TEMPO_01, TEMPO_02, ROM_01, ROM_02, ROM_03,
        SESS_01, SESS_02, SPLIT_01, SPLIT_02, PROG_01, PROG_02, PROG_03, PROG_04, PROG_05, PLAT_01, PLAT_02, PLAT_03,
        DLD_01, DLD_02, DLD_03, DLD_04, E1RM_01, E1RM_02, E1RM_03, E1RM_04, RDY_01, RDY_02, RDY_03, RDY_04, RDY_05, RDY_06,
        CHK_01, RED_01, RED_02, RED_03, WU_01, WU_02, WU_03, WU_04, WU_05, WU_06, MOB_01, MOB_02, MOB_03, MOB_04, MOB_05, MOB_06, MOB_07,
        CAR_01, CAR_02, CAR_03, CAR_04, CAR_05, CAR_06, CAR_07, CAR_08, CAR_09,
        NUT_01, NUT_02, NUT_03, NUT_04, NUT_05, NUT_06, NUT_07, NUT_08, NUT_09, NUT_10, NUT_11, NUT_12, NUT_13, NUT_14, NUT_15, NUT_16, NUT_17, NUT_18,
        SUP_01, SUP_02, SUP_03, SUP_04, SUP_05, SUP_06, SUP_07, SUP_08, SUP_09, SUP_10, SUP_11, SUP_12,
        SLP_01, SLP_02, SLP_03, STP_01, STP_02, STR_01, STR_02, AGE_01, AGE_02, AGE_03, LAY_01, LAY_02, LAY_03,
        TRV_01, TRV_02, TRV_03, TRV_04, TIME_01, TIME_02, TIME_03, INJ_00, INJ_01, INJ_02, INJ_03, INJ_04,
        SEL, SUB, REBAL, TIER, R4_2_6, R4_4_5, R4_5_4, R4_5_5, RIR_CAP, ROUNDING, OVERRIDE,
        TIME_L1, TIME_L2, TIME_L3, TIME_L4, TIME_L5, TIME_L6, TIME_L7,
    )
}
