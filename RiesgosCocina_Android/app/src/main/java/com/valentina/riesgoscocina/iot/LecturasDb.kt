package com.valentina.riesgoscocina.iot

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

// Almacenamiento local de lecturas (funciona sin internet)
class LecturasDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "lecturas.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE lecturas (
                dispositivo TEXT NOT NULL,
                id INTEGER NOT NULL,
                temp REAL,
                hum REAL,
                gas INTEGER NOT NULL,
                armado INTEGER NOT NULL,
                alarma INTEGER NOT NULL,
                recibido INTEGER NOT NULL,
                sincronizada INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (dispositivo, id))"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // Guarda la lectura si no existe; devuelve true si es nueva
    fun guardar(l: Lectura): Boolean {
        val valores = ContentValues().apply {
            put("dispositivo", l.dispositivo)
            put("id", l.id)
            put("temp", l.temp)
            put("hum", l.hum)
            put("gas", l.gas)
            put("armado", if (l.armado) 1 else 0)
            put("alarma", if (l.alarma) 1 else 0)
            put("recibido", l.recibido)
        }
        return writableDatabase.insertWithOnConflict(
            "lecturas", null, valores, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    // Guarda una lectura recuperada de DynamoDB (ya sincronizada). Devuelve true si no existía en el teléfono
    fun guardarDeNube(l: Lectura): Boolean {
        val valores = ContentValues().apply {
            put("dispositivo", l.dispositivo)
            put("id", l.id)
            put("temp", l.temp)
            put("hum", l.hum)
            put("gas", l.gas)
            put("armado", if (l.armado) 1 else 0)
            put("alarma", if (l.alarma) 1 else 0)
            put("recibido", l.recibido)
            put("sincronizada", 1)
        }
        val nueva = writableDatabase.insertWithOnConflict(
            "lecturas", null, valores, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
        if (!nueva) marcarSincronizadas(l.dispositivo, listOf(l.id))
        return nueva
    }

    // Último dispositivo conocido (para recuperar sus lecturas aunque el ESP32 no esté en línea)
    fun ultimoDispositivo(): String? =
        readableDatabase.rawQuery(
            "SELECT dispositivo FROM lecturas ORDER BY recibido DESC LIMIT 1", null
        ).use { if (it.moveToFirst()) it.getString(0) else null }

    // Borra solo la copia local de lo que ya está seguro en DynamoDB; las pendientes no se tocan
    fun borrarSincronizadas(): Int = writableDatabase.delete("lecturas", "sincronizada=1", null)

    fun existe(dispositivo: String, id: Long): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM lecturas WHERE dispositivo=? AND id=?",
            arrayOf(dispositivo, id.toString())
        ).use { it.moveToFirst() }

    fun recientes(limite: Int): List<Lectura> =
        consultar("SELECT * FROM lecturas ORDER BY recibido DESC, id DESC LIMIT $limite")

    fun alertas(limite: Int): List<Lectura> =
        consultar("SELECT * FROM lecturas WHERE alarma=1 ORDER BY recibido DESC, id DESC LIMIT $limite")

    fun pendientes(limite: Int): List<Lectura> =
        consultar("SELECT * FROM lecturas WHERE sincronizada=0 ORDER BY id LIMIT $limite")

    fun marcarSincronizadas(dispositivo: String, ids: List<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach {
                db.execSQL(
                    "UPDATE lecturas SET sincronizada=1 WHERE dispositivo=? AND id=?",
                    arrayOf<Any?>(dispositivo, it)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun contar(soloPendientes: Boolean): Int {
        val sql = if (soloPendientes) "SELECT COUNT(*) FROM lecturas WHERE sincronizada=0"
        else "SELECT COUNT(*) FROM lecturas"
        return readableDatabase.rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private fun consultar(sql: String): List<Lectura> =
        readableDatabase.rawQuery(sql, null).use { c ->
            val lista = mutableListOf<Lectura>()
            while (c.moveToNext()) lista.add(leer(c))
            lista
        }

    private fun leer(c: Cursor) = Lectura(
        dispositivo = c.getString(c.getColumnIndexOrThrow("dispositivo")),
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        temp = c.getColumnIndexOrThrow("temp").let { if (c.isNull(it)) null else c.getDouble(it) },
        hum = c.getColumnIndexOrThrow("hum").let { if (c.isNull(it)) null else c.getDouble(it) },
        gas = c.getInt(c.getColumnIndexOrThrow("gas")),
        armado = c.getInt(c.getColumnIndexOrThrow("armado")) == 1,
        alarma = c.getInt(c.getColumnIndexOrThrow("alarma")) == 1,
        recibido = c.getLong(c.getColumnIndexOrThrow("recibido")),
        sincronizada = c.getInt(c.getColumnIndexOrThrow("sincronizada")) == 1
    )
}
