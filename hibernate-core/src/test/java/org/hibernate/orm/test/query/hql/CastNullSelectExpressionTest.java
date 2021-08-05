/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.orm.test.query.hql;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.Table;

import org.hibernate.testing.TestForIssue;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.NotImplementedYet;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * @author Gail Badner
 */
@DomainModel( annotatedClasses = CastNullSelectExpressionTest.Person.class )
@SessionFactory
@NotImplementedYet(
		reason = "Combination of https://github.com/hibernate/hibernate-orm/discussions/3921 and https://github.com/hibernate/hibernate-orm/discussions/3889",
		strict = false
)
public class CastNullSelectExpressionTest {

	@Test
	@TestForIssue(jiraKey = "HHH-10757")
	public void testSelectCastNull(SessionFactoryScope scope) {
		scope.inTransaction(
				(session) -> {
					Object[] result = (Object[]) session.createQuery(
							"select firstName, cast( null as string ), lastName from Person where lastName='Munster'"
					).uniqueResult();

					assertEquals( 3, result.length );
					assertEquals( "Herman", result[0] );
					assertNull( result[1] );
					assertEquals( "Munster", result[2] );
				}
		);
	}

	@Test
	@TestForIssue(jiraKey = "HHH-10757")
	public void testSelectNewCastNull(SessionFactoryScope scope) {
		scope.inTransaction(
				(session) -> {
					Person result = (Person) session.createQuery(
							"select Person( id, firstName, cast( null as string ), lastName ) from Person where lastName='Munster'"
					).uniqueResult();
					assertEquals( "Herman", result.firstName );
					assertNull( result.middleName );
					assertEquals( "Munster", result.lastName );
				}
		);
	}

	@BeforeEach
	public void createTestData(SessionFactoryScope scope) {
		scope.inTransaction(
				(session) -> {
					Person person = new Person();
					person.firstName = "Herman";
					person.middleName = "Joseph";
					person.lastName = "Munster";
					session.persist( person );
				}
		);
	}

	@AfterEach
	public void dropTestData(SessionFactoryScope scope) {
		scope.inTransaction(
				(session) -> session.createQuery( "delete Person" ).executeUpdate()
		);
	}

	@Entity( name= "Person" )
	@Table(name = "PERSON")
	public static class Person {
		@Id
		@GeneratedValue
		private long id;

		private String firstName;

		private String middleName;

		private String lastName;

		private Integer age;

		Person() {
		}

		public Person(long id, String firstName, String middleName, String lastName) {
			this.id = id;
			this.firstName = firstName;
			this.middleName = middleName;
			this.lastName = lastName;
		}

		public Person(long id, String firstName, Integer age, String lastName) {
			this.id = id;
			this.firstName = firstName;
			this.middleName = null;
			this.lastName = lastName;
			this.age = age;
		}

	}
}