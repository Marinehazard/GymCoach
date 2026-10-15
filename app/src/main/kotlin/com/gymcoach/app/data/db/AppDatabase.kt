// app/src/main/kotlin/com/gymcoach/app/data/db/AppDatabase.kt
package com.gymcoach.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ProfileEntity::class, BodyMetricEntity::class, GymEntity::class, SessionEntity::class, SetLogEntity::class,
        AdjustmentEntity::class, ProgressionStateEntity::class, OverrideEntity::class, PlanSnapshotEntity::class,
        NutritionStateEntity::class, ReadinessEntity::class, WeeklyCheckinEntity::class, CheckinQuestionEntity::class,
        NutritionLogEntity::class, CardioLogEntity::class, ChatConversationEntity::class, ChatMessageEntity::class,
        ProposalEntity::class, SpendRecordEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun gymDao(): GymDao
    abstract fun planDao(): PlanDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun progressionDao(): ProgressionDao
    abstract fun metricsDao(): MetricsDao
    abstract fun coachDao(): CoachDao
}
