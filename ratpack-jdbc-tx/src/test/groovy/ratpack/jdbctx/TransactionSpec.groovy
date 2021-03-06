/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ratpack.jdbctx

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import groovy.sql.Sql
import org.h2.jdbcx.JdbcDataSource
import ratpack.exec.Blocking
import ratpack.exec.Promise
import ratpack.test.exec.ExecHarness
import spock.lang.Specification

import javax.sql.DataSource
import java.sql.Connection

class TransactionSpec extends Specification {

  DataSource ds = Transaction.dataSource(createDs())
  Sql sql = new Sql(ds)
  ratpack.func.Factory<Connection> factory = ds.&getConnection
  def tx = Transaction.create(factory)

  DataSource createDs() {
    def config = new HikariConfig()
    def ds = new JdbcDataSource()
    ds.setURL("jdbc:h2:mem:${getClass().name}")
    config.setDataSource(ds)
    new HikariDataSource(config)
  }

  def setup() {
    sql.execute("DROP TABLE IF EXISTS foo")
    sql.execute('''
            CREATE TABLE foo (
                id VARCHAR(10) NOT NULL,
                name VARCHAR(50),
                PRIMARY KEY(id)
            )
        ''')
  }

  private <T> T run(ratpack.func.Factory<? extends Promise<T>> exec) {
    ExecHarness.yieldSingle {
      exec.create()
    }.valueOrThrow
  }

  private void insert(String id) {
    sql.execute "INSERT INTO foo (id, name) VALUES ($id, 'p')"
  }

  boolean findById(String id) {
    sql.rows("SELECT * FROM foo WHERE id = $id").size() == 1
  }

  void "successful insert commits data"() {
    when:
    run {
      tx.wrap(Blocking.get { insert("1") })
    }

    then:
    findById("1")
  }

  void "rolls back on failure"() {
    when:
    run {
      tx.wrap(
        Blocking.get {
          insert("1")
          throw new Error("!")
        }
      )
    }

    then:
    thrown(Error)
    !findById("1")
  }

  def "rolls back outer when inner fails"() {
    when:
    run {
      tx.wrap(
        Blocking.get {
          insert("1")
        }.flatMap {
          tx.wrap(Blocking.get {
            insert("2")
            throw new RuntimeException("!")
          })
        }.flatMap {
          tx.wrap(Blocking.get { insert("3") })
        }
      )
    }

    then:
    thrown RuntimeException
    !findById("1")
    !findById("2")
    !findById("3")
  }

  def "rolls back inner when outer fails"() {
    when:
    run {
      tx.wrap(Blocking.get {
        insert("1")
      }.flatMap {
        tx.wrap(Blocking.get {
          insert("2")
        })
      }.flatMap {
        tx.wrap(Blocking.get {
          insert("3")
          throw new RuntimeException("!")
        })
      })
    }

    then:
    thrown RuntimeException
    !findById("1")
    !findById("2")
    !findById("3")
  }

  def "can use connection after commit"() {
    when:
    run {
      tx.wrap(
        Blocking.get {
          insert("1")
        }.flatMap {
          tx.wrap(Blocking.get {
            insert("2")
          })
        }
      ).blockingMap {
        insert("3")
      }
    }

    then:
    findById("1")
    findById("2")
    findById("3")
  }

  def "can use connection after rollback"() {
    when:
    run {
      tx.wrap(Blocking.get {
        insert("1")
      }.flatMap {
        tx.wrap(Blocking.get {
          insert("2")
          throw new Exception("!")
        })
      }).mapError {
        "foo"
      }.blockingMap {
        insert("3")
      }
    }

    then:
    !findById("1")
    !findById("2")
    findById("3")
  }

  def "nested transactions are rolled back to savepoint, but outer transaction can succeed"() {
    when:
    run {
      tx.wrap(
        Blocking.get {
          insert("1")
        }.flatMap {
          tx.wrap(
            Blocking.get {
              insert("2")
              throw new Exception("!")
            }
          )
        }.mapError {
          "foo" // recover from error
        }.blockingMap {
          insert("3")
        }
      )
    }

    then:
    findById("1")
    !findById("2")
    findById("3")
  }

  def "nested transactions are rolled back to savepoint, and outer transaction can fail"() {
    when:
    run {
      tx.wrap(
        Blocking.get {
          insert("1")
        }.flatMap {
          tx.wrap(
            Blocking.get {
              insert("2")
              throw new Exception("!")
            }
          )
        }.mapError {
          "foo" // recover from error
        }.blockingMap {
          insert("3")
        }.map {
          throw new Exception("!!") // then fail
        }
      )
    }

    then:
    def e = thrown Exception
    e.message == "!!"
    !findById("1")
    !findById("2")
    !findById("3")
  }

  def "can manually demarcate tx boundaries"() {
    when:
    run {
      tx.begin().next {
        assert Transaction.current().get() == tx
        insert("1")
        tx.begin().then {
          insert("2")
          tx.rollback().then {
            tx.commit().then()
          }
        }
      }.promise()
    }

    then:
    findById("1")
    !findById("2")
    !Transaction.current().present
    !tx.connection.present
  }

}
