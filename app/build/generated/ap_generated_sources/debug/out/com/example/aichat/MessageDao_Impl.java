package com.example.aichat;

import android.database.Cursor;
import androidx.annotation.NonNull;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.processing.Generated;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class MessageDao_Impl implements MessageDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<Message> __insertionAdapterOfMessage;

  private final SharedSQLiteStatement __preparedStmtOfDeleteBySession;

  public MessageDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfMessage = new EntityInsertionAdapter<Message>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `message` (`id`,`sessionId`,`role`,`content`,`createdAt`) VALUES (nullif(?, 0),?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement, final Message entity) {
        statement.bindLong(1, entity.id);
        if (entity.sessionId == null) {
          statement.bindNull(2);
        } else {
          statement.bindString(2, entity.sessionId);
        }
        statement.bindLong(3, entity.role);
        if (entity.content == null) {
          statement.bindNull(4);
        } else {
          statement.bindString(4, entity.content);
        }
        statement.bindLong(5, entity.createdAt);
      }
    };
    this.__preparedStmtOfDeleteBySession = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM message WHERE sessionId = ?";
        return _query;
      }
    };
  }

  @Override
  public long insert(final Message message) {
    __db.assertNotSuspendingTransaction();
    __db.beginTransaction();
    try {
      final long _result = __insertionAdapterOfMessage.insertAndReturnId(message);
      __db.setTransactionSuccessful();
      return _result;
    } finally {
      __db.endTransaction();
    }
  }

  @Override
  public void deleteBySession(final String sessionId) {
    __db.assertNotSuspendingTransaction();
    final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteBySession.acquire();
    int _argIndex = 1;
    if (sessionId == null) {
      _stmt.bindNull(_argIndex);
    } else {
      _stmt.bindString(_argIndex, sessionId);
    }
    try {
      __db.beginTransaction();
      try {
        _stmt.executeUpdateDelete();
        __db.setTransactionSuccessful();
      } finally {
        __db.endTransaction();
      }
    } finally {
      __preparedStmtOfDeleteBySession.release(_stmt);
    }
  }

  @Override
  public List<Message> getBySession(final String sessionId) {
    final String _sql = "SELECT * FROM message WHERE sessionId = ? ORDER BY createdAt ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    if (sessionId == null) {
      _statement.bindNull(_argIndex);
    } else {
      _statement.bindString(_argIndex, sessionId);
    }
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
      final int _cursorIndexOfSessionId = CursorUtil.getColumnIndexOrThrow(_cursor, "sessionId");
      final int _cursorIndexOfRole = CursorUtil.getColumnIndexOrThrow(_cursor, "role");
      final int _cursorIndexOfContent = CursorUtil.getColumnIndexOrThrow(_cursor, "content");
      final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
      final List<Message> _result = new ArrayList<Message>(_cursor.getCount());
      while (_cursor.moveToNext()) {
        final Message _item;
        _item = new Message();
        _item.id = _cursor.getLong(_cursorIndexOfId);
        if (_cursor.isNull(_cursorIndexOfSessionId)) {
          _item.sessionId = null;
        } else {
          _item.sessionId = _cursor.getString(_cursorIndexOfSessionId);
        }
        _item.role = _cursor.getInt(_cursorIndexOfRole);
        if (_cursor.isNull(_cursorIndexOfContent)) {
          _item.content = null;
        } else {
          _item.content = _cursor.getString(_cursorIndexOfContent);
        }
        _item.createdAt = _cursor.getLong(_cursorIndexOfCreatedAt);
        _result.add(_item);
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public List<String> getAllSessionIds() {
    final String _sql = "SELECT DISTINCT sessionId FROM message ORDER BY createdAt DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final List<String> _result = new ArrayList<String>(_cursor.getCount());
      while (_cursor.moveToNext()) {
        final String _item;
        if (_cursor.isNull(0)) {
          _item = null;
        } else {
          _item = _cursor.getString(0);
        }
        _result.add(_item);
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public List<SessionSummary> getRecentSessions() {
    final String _sql = "SELECT m.sessionId as sessionId, (SELECT content FROM message m2 WHERE m2.sessionId = m.sessionId AND m2.role = 0 ORDER BY m2.createdAt ASC LIMIT 1) as title, MAX(m.createdAt) as lastAt FROM message m GROUP BY m.sessionId ORDER BY lastAt DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _cursorIndexOfSessionId = 0;
      final int _cursorIndexOfTitle = 1;
      final int _cursorIndexOfLastAt = 2;
      final List<SessionSummary> _result = new ArrayList<SessionSummary>(_cursor.getCount());
      while (_cursor.moveToNext()) {
        final SessionSummary _item;
        _item = new SessionSummary();
        if (_cursor.isNull(_cursorIndexOfSessionId)) {
          _item.sessionId = null;
        } else {
          _item.sessionId = _cursor.getString(_cursorIndexOfSessionId);
        }
        if (_cursor.isNull(_cursorIndexOfTitle)) {
          _item.title = null;
        } else {
          _item.title = _cursor.getString(_cursorIndexOfTitle);
        }
        _item.lastAt = _cursor.getLong(_cursorIndexOfLastAt);
        _result.add(_item);
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public List<SessionSummary> getAllSessions() {
    final String _sql = "SELECT m.sessionId as sessionId, (SELECT content FROM message m2 WHERE m2.sessionId = m.sessionId AND m2.role = 0 ORDER BY m2.createdAt ASC LIMIT 1) as title, MAX(m.createdAt) as lastAt FROM message m GROUP BY m.sessionId ORDER BY lastAt DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _cursorIndexOfSessionId = 0;
      final int _cursorIndexOfTitle = 1;
      final int _cursorIndexOfLastAt = 2;
      final List<SessionSummary> _result = new ArrayList<SessionSummary>(_cursor.getCount());
      while (_cursor.moveToNext()) {
        final SessionSummary _item;
        _item = new SessionSummary();
        if (_cursor.isNull(_cursorIndexOfSessionId)) {
          _item.sessionId = null;
        } else {
          _item.sessionId = _cursor.getString(_cursorIndexOfSessionId);
        }
        if (_cursor.isNull(_cursorIndexOfTitle)) {
          _item.title = null;
        } else {
          _item.title = _cursor.getString(_cursorIndexOfTitle);
        }
        _item.lastAt = _cursor.getLong(_cursorIndexOfLastAt);
        _result.add(_item);
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
