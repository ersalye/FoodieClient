package com.fpondarts.foodie.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.fpondarts.foodie.data.db.entity.User


@Database(
    entities = [User::class],
    version = 1
)
abstract class FoodieDatabase: RoomDatabase(){

    abstract fun getUserDao() : UserDao

    companion object{
        @Volatile
        private var instance: FoodieDatabase? = null
        private val LOCK = Any()

        operator fun invoke(context: Context) = instance ?: synchronized(LOCK) {
            instance?:buildDatabase(context).also {
                instance = it
            }
        }

        private fun buildDatabase(context:Context) =
            Room.databaseBuilder(
                context.applicationContext,
                FoodieDatabase::class.java,
                "FoodieDatabase.db")
                .build()

    }


}