/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.community.dialect;

import org.hibernate.HibernateException;
import org.hibernate.NotYetImplementedFor6Exception;
import org.hibernate.dialect.BooleanDecoder;
import org.hibernate.dialect.Dialect;
import org.hibernate.query.NullOrdering;
import org.hibernate.cfg.Environment;
import org.hibernate.dialect.function.CommonFunctionFactory;
import org.hibernate.community.dialect.identity.FirebirdIdentityColumnSupport;
import org.hibernate.dialect.identity.IdentityColumnSupport;
import org.hibernate.dialect.pagination.LimitHandler;
import org.hibernate.dialect.pagination.OffsetFetchLimitHandler;
import org.hibernate.community.dialect.pagination.SkipFirstLimitHandler;
import org.hibernate.community.dialect.sequence.FirebirdSequenceSupport;
import org.hibernate.community.dialect.sequence.InterbaseSequenceSupport;
import org.hibernate.dialect.sequence.SequenceSupport;
import org.hibernate.engine.jdbc.Size;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.engine.jdbc.env.spi.IdentifierHelper;
import org.hibernate.engine.jdbc.env.spi.IdentifierHelperBuilder;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.LockAcquisitionException;
import org.hibernate.exception.LockTimeoutException;
import org.hibernate.exception.spi.SQLExceptionConversionDelegate;
import org.hibernate.exception.spi.ViolatedConstraintNameExtractor;
import org.hibernate.internal.util.JdbcExceptionHelper;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.spi.RuntimeModelCreationContext;
import org.hibernate.query.CastType;
import org.hibernate.query.TemporalUnit;
import org.hibernate.query.spi.QueryEngine;
import org.hibernate.query.sqm.function.SqmFunctionRegistry;
import org.hibernate.query.sqm.mutation.internal.idtable.AfterUseAction;
import org.hibernate.query.sqm.mutation.internal.idtable.GlobalTemporaryTableStrategy;
import org.hibernate.query.sqm.mutation.internal.idtable.IdTable;
import org.hibernate.query.sqm.mutation.internal.idtable.TempIdTableExporter;
import org.hibernate.query.sqm.mutation.spi.SqmMultiTableMutationStrategy;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.SqlAstTranslatorFactory;
import org.hibernate.sql.ast.spi.StandardSqlAstTranslatorFactory;
import org.hibernate.sql.ast.tree.Statement;
import org.hibernate.sql.exec.spi.JdbcOperation;
import org.hibernate.community.dialect.sequence.SequenceInformationExtractorFirebirdDatabaseImpl;
import org.hibernate.tool.schema.extract.internal.SequenceNameExtractorImpl;
import org.hibernate.tool.schema.extract.spi.SequenceInformationExtractor;
import org.hibernate.type.StandardBasicTypes;
import org.hibernate.type.descriptor.jdbc.JdbcTypeDescriptor;
import org.hibernate.type.descriptor.jdbc.spi.JdbcTypeDescriptorRegistry;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.temporal.TemporalAccessor;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.persistence.TemporalType;

import static org.hibernate.type.descriptor.DateTimeUtils.formatAsTimestampWithMillis;

/**
 * An SQL dialect for Firebird 2.0 and above.
 *
 * @author Reha CENANI
 * @author Gavin King
 * @author Mark Rotteveel
 */
public class FirebirdDialect extends Dialect {

	private final int version;

	public FirebirdDialect() {
		this( 250 );
	}

	public FirebirdDialect(DialectResolutionInfo info) {
		this( info.getDatabaseMajorVersion() * 100 + info.getDatabaseMinorVersion() * 10 );
	}

	// KNOWN LIMITATIONS:

	// * no support for format()
	// * (Firebird 3 and earlier) extremely low maximum decimal precision (18)
	//   making BigInteger/BigDecimal support useless
	// * can't select a parameter unless wrapped in a
	//   cast (not even when wrapped in a function call)

	public FirebirdDialect(int version) {
		super();
		this.version = version;

		if ( getVersion() < 300 ) {
			//'boolean' type introduced in 3.0
			registerColumnType( Types.BOOLEAN, "smallint" );
		}

		registerColumnType( Types.TINYINT, "smallint" );

		// Note: according to the documentation, Firebird has
		// just two floating point types:
		// - single precision 'float' (32 bit), and
		// - 'double precision' (64 bit).
		// However, it turns out that Firebird actually supports
		// the ANSI types 'real', 'float(p)', 'double precision'.
		// So we don't override anything here.

		//no precision for 'timestamp' type
		registerColumnType( Types.TIMESTAMP, "timestamp" );
		if ( getVersion() < 400 ) {
			// No time zone support, map to without time zone types
			registerColumnType( Types.TIMESTAMP_WITH_TIMEZONE, "timestamp" );
			registerColumnType( Types.TIME_WITH_TIMEZONE, "time" );
		}
		else {
			registerColumnType( Types.TIMESTAMP_WITH_TIMEZONE, "timestamp with time zone" );
		}
		// Single byte character sets can be 32_765 characters, but assume use of UTF8
		registerColumnType( Types.VARCHAR, 8_191, "varchar($l)" );
		registerColumnType( Types.VARCHAR, "blob sub_type text" );

		if ( getVersion() < 400 ) {
			registerColumnType( Types.BINARY, 32_767, "char($l) character set octets" );
		}
		else {
			registerColumnType( Types.BINARY, 32_767, "binary($l)" );
		}
		registerColumnType( Types.BINARY, "blob sub_type binary" );

		if ( getVersion() < 400 ) {
			registerColumnType( Types.VARBINARY, 32_765, "varchar($l) character set octets" );
		}
		else {
			registerColumnType( Types.VARBINARY, 32_765, "varbinary($l)" );
		}
		registerColumnType( Types.VARBINARY, "blob sub_type binary" );

		registerColumnType( Types.BLOB, "blob sub_type binary" );
		registerColumnType( Types.CLOB, "blob sub_type text" );
		registerColumnType( Types.NCLOB, "blob sub_type text" ); // Firebird doesn't have NCLOB, but Jaybird emulates NCLOB support

		getDefaultProperties().setProperty( Environment.STATEMENT_BATCH_SIZE, NO_BATCH );
	}

	@Override
	public int getVersion() {
		return version;
	}

	@Override
	public boolean supportsTimezoneTypes() {
		return getVersion() >= 400;
	}

	@Override
	public JdbcTypeDescriptor resolveSqlTypeDescriptor(
			int jdbcTypeCode,
			int precision,
			int scale,
			JdbcTypeDescriptorRegistry jdbcTypeDescriptorRegistry) {
		if ( jdbcTypeCode == Types.BIT ) {
			return jdbcTypeDescriptorRegistry.getDescriptor( Types.BOOLEAN );
		}
		return super.resolveSqlTypeDescriptor( jdbcTypeCode, precision, scale, jdbcTypeDescriptorRegistry );
	}

	@Override
	public int getPreferredSqlTypeCodeForBoolean() {
		return getVersion() < 300
				? Types.BIT
				: super.getPreferredSqlTypeCodeForBoolean();
	}

	@Override
	public String getTypeName(int code, Size size) throws HibernateException {
		if ( getVersion() < 400 ) {
			//precision of a Firebird 3 and earlier 'float(p)' represents
			//decimal digits instead of binary digits
			return super.getTypeName( code, binaryToDecimalPrecision( code, size ) );
		}
		else {
			// Firebird 4 and higher supports standard 'float(p)' (with precision in binary digits)
			return super.getTypeName( code, size );
		}
	}

	@Override
	public int getFloatPrecision() {
		return getVersion() < 400
				? 21 // -> 7 decimal digits (actually 24, but needed for Dialect#binaryToDecimalPrecision(int,size))
				: 24;
	}

	@Override
	public int getDefaultTimestampPrecision() {
		// Formally, Firebird has a (fixed) precision of 4 (100 microseconds),
		// but things like CURRENT_TIMESTAMP produce values with a maximum of 3, so report that
		return 3;
	}

	@Override
	public void initializeFunctionRegistry(QueryEngine queryEngine) {
		super.initializeFunctionRegistry( queryEngine );

		CommonFunctionFactory.concat_pipeOperator( queryEngine );
		CommonFunctionFactory.cot( queryEngine );
		CommonFunctionFactory.cosh( queryEngine );
		CommonFunctionFactory.sinh( queryEngine );
		CommonFunctionFactory.tanh( queryEngine );
		if ( getVersion() >= 300 ) {
			CommonFunctionFactory.moreHyperbolic( queryEngine );
			CommonFunctionFactory.stddevPopSamp( queryEngine );
			CommonFunctionFactory.varPopSamp( queryEngine );
			CommonFunctionFactory.covarPopSamp( queryEngine );
			CommonFunctionFactory.corr( queryEngine );
			CommonFunctionFactory.regrLinearRegressionAggregates( queryEngine );
		}
		CommonFunctionFactory.log( queryEngine );
		CommonFunctionFactory.log10( queryEngine );
		CommonFunctionFactory.pi( queryEngine );
		CommonFunctionFactory.rand( queryEngine );
		CommonFunctionFactory.sinh( queryEngine );
		CommonFunctionFactory.tanh( queryEngine );
		CommonFunctionFactory.cosh( queryEngine );
		CommonFunctionFactory.trunc( queryEngine );
		CommonFunctionFactory.octetLength( queryEngine );
		CommonFunctionFactory.bitLength( queryEngine );
		CommonFunctionFactory.substringFromFor( queryEngine );
		CommonFunctionFactory.overlay( queryEngine );
		CommonFunctionFactory.position( queryEngine );
		CommonFunctionFactory.reverse( queryEngine );
		CommonFunctionFactory.bitandorxornot_binAndOrXorNot( queryEngine );
		CommonFunctionFactory.leastGreatest_minMaxValue( queryEngine );

		SqmFunctionRegistry functionRegistry = queryEngine.getSqmFunctionRegistry();
		functionRegistry.registerBinaryTernaryPattern(
				"locate",
				StandardBasicTypes.INTEGER,
				"position(?1 in ?2)",
				"position(?1, ?2, ?3)"
		).setArgumentListSignature( "(pattern, string[, start])" );
		functionRegistry.namedDescriptorBuilder( "ascii_val" )
				.setExactArgumentCount( 1 )
				.setInvariantType( StandardBasicTypes.SHORT )
				.register();
		functionRegistry.registerAlternateKey( "ascii", "ascii_val" );
		functionRegistry.namedDescriptorBuilder( "ascii_char" )
				.setExactArgumentCount( 1 )
				.setInvariantType( StandardBasicTypes.CHARACTER )
				.register();
		functionRegistry.registerAlternateKey( "chr", "ascii_char" );
		functionRegistry.registerAlternateKey( "char", "ascii_char" );
		functionRegistry.registerPattern(
				"radians",
				"((?1)*pi()/180e0)",
				StandardBasicTypes.DOUBLE
		);
		functionRegistry.registerPattern(
				"degrees",
				"((?1)*180e0/pi())",
				StandardBasicTypes.DOUBLE
		);

		if ( getVersion() >= 400 ) {
			Arrays.asList( "md5", "sha1", "sha256", "sha512" )
					.forEach( hash -> functionRegistry.registerPattern(
							hash,
							"crypt_hash(?1 using " + hash + ")",
							StandardBasicTypes.BINARY
					) );
			functionRegistry.registerAlternateKey( "sha", "sha1" );
			functionRegistry.registerPattern(
					"crc32",
					"hash(?1 using crc32)",
					StandardBasicTypes.INTEGER
			);
		}
	}

	@Override
	public String currentLocalTime() {
		if ( supportsTimezoneTypes() ) {
			return "localtime";
		}
		else {
			return super.currentLocalTime();
		}
	}

	@Override
	public String currentLocalTimestamp() {
		if ( supportsTimezoneTypes() ) {
			return "localtimestamp";
		}
		else {
			return super.currentLocalTimestamp();
		}
	}

	@Override
	public SqlAstTranslatorFactory getSqlAstTranslatorFactory() {
		return new StandardSqlAstTranslatorFactory() {
			@Override
			protected <T extends JdbcOperation> SqlAstTranslator<T> buildTranslator(
					SessionFactoryImplementor sessionFactory, Statement statement) {
				return new FirebirdSqlAstTranslator<>( sessionFactory, statement );
			}
		};
	}

	@Override
	public boolean supportsTruncateWithCast(){
		return false;
	}

	/**
	 * Firebird 2.5 doesn't have a real {@link java.sql.Types#BOOLEAN}
	 * type, so...
	 */
	@Override
	public String castPattern(CastType from, CastType to) {
		String result;
		switch ( to ) {
			case INTEGER:
			case LONG:
				result = BooleanDecoder.toInteger( from );
				if ( result != null ) {
					return result;
				}
				break;
			case BOOLEAN:
				result = BooleanDecoder.toBoolean( from );
				if ( result != null ) {
					return result;
				}
				break;
			case INTEGER_BOOLEAN:
				result = BooleanDecoder.toIntegerBoolean( from );
				if ( result != null ) {
					return result;
				}
				break;
			case YN_BOOLEAN:
				result = BooleanDecoder.toYesNoBoolean( from );
				if ( result != null ) {
					return result;
				}
				break;
			case TF_BOOLEAN:
				result = BooleanDecoder.toTrueFalseBoolean( from );
				if ( result != null ) {
					return result;
				}
				break;
			case STRING:
				result = BooleanDecoder.toString( from );
				if ( result != null ) {
					// trim converts to varchar to prevent padding with spaces
					return "trim(" + result + ")";
				}
				break;
		}
		return super.castPattern( from, to );
	}

	@Override
	public long getFractionalSecondPrecisionInNanos() {
		// Formally, Firebird can store values with 100 microsecond precision (100_000 nanoseconds).
		// However, some functions (e.g. CURRENT_TIMESTAMP) will only return values with millisecond precision
		// So, we report millisecond precision
		return 1_000_000; //milliseconds
	}

	/**
	 * Firebird extract() function returns {@link TemporalUnit#DAY_OF_WEEK}
	 * numbered from 0 to 6, and {@link TemporalUnit#DAY_OF_YEAR} numbered
	 * for 0. This isn't consistent with what most other databases do, so
	 * here we adjust the result by generating {@code (extract(unit,arg)+1))}.
	 */
	@Override
	public String extractPattern(TemporalUnit unit) {
		switch ( unit ) {
			case DAY_OF_WEEK:
			case DAY_OF_YEAR:
				return "(" + super.extractPattern( unit ) + "+1)";
			case QUARTER:
				return "((extract(month from ?2)+2)/3)";
			default:
				return super.extractPattern( unit );
		}
	}

	@Override
	public String timestampaddPattern(TemporalUnit unit, TemporalType temporalType) {
		switch ( unit ) {
			case NATIVE:
				return "dateadd((?2) millisecond to ?3)";
			case NANOSECOND:
				return "dateadd((?2)/1e6 millisecond to ?3)";
			case WEEK:
				return "dateadd((?2)*7 day to ?3)";
			case QUARTER:
				return "dateadd((?2)*3 month to ?3)";
			default:
				return "dateadd(?2 ?1 to ?3)";
		}
	}

	@Override
	public String timestampdiffPattern(TemporalUnit unit, TemporalType fromTemporalType, TemporalType toTemporalType) {
		switch ( unit ) {
			case NATIVE:
				return "datediff(millisecond from ?2 to ?3)";
			case NANOSECOND:
				return "datediff(millisecond from ?2 to ?3)*1e6";
			case WEEK:
				return "datediff(day from ?2 to ?3)/7";
			case QUARTER:
				return "datediff(month from ?2 to ?3)/3";
			default:
				return "datediff(?1 from ?2 to ?3)";
		}
	}

	@Override
	public boolean supportsTemporalLiteralOffset() {
		return getVersion() >= 400;
	}

	@Override
	public int getDefaultDecimalPrecision() {
		return getVersion() < 400 ? 18 : 38;
	}

	@Override
	public String getAddColumnString() {
		return "add";
	}

	@Override
	public String getNoColumnsInsertString() {
		return "default values";
	}

	@Override
	public int getMaxAliasLength() {
		return getVersion() < 400 ? 20 : 52;
	}

	public IdentifierHelper buildIdentifierHelper(
			IdentifierHelperBuilder builder,
			DatabaseMetaData dbMetaData) throws SQLException {
		// Any use of keywords as identifiers will result in token unknown error, so enable auto quote always
		builder.setAutoQuoteKeywords( true );

		// Additional reserved words
		// The Hibernate list of SQL:2003 reserved words doesn't contain all SQL:2003 reserved words,
		// and Firebird is finicky when it comes to reserved words
		int version = getVersion();
		if ( version >= 300 ) {
			builder.applyReservedWords(
					"AVG", "BOOLEAN", "CHARACTER_LENGTH", "CHAR_LENGTH", "CORR", "COUNT",
					"COVAR_POP", "COVAR_SAMP", "EXTRACT", "LOWER", "MAX", "MIN", "OCTET_LENGTH", "POSITION",
					"REGR_AVGX", "REGR_AVGY", "REGR_COUNT", "REGR_INTERCEPT", "REGR_R2", "REGR_SLOPE", "REGR_SXX",
					"REGR_SXY", "REGR_SYY", "STDDEV_POP", "STDDEV_SAMP", "SUM", "TRIM", "UPPER", "VAR_POP",
					"VAR_SAMP" );
		}
		else {
			builder.applyReservedWords(
					"AVG", "CHARACTER_LENGTH", "CHAR_LENGTH", "COUNT", "EXTRACT", "LOWER", "MAX", "MIN", "OCTET_LENGTH",
					"POSITION", "SUM", "TRIM", "UPPER" );
		}

		return super.buildIdentifierHelper( builder, dbMetaData );
	}

	@Override
	public boolean canCreateSchema() {
		return false;
	}

	@Override
	public String[] getCreateSchemaCommand(String schemaName) {
		throw new UnsupportedOperationException( "No create schema syntax supported by " + getClass().getName() );
	}

	@Override
	public String[] getDropSchemaCommand(String schemaName) {
		throw new UnsupportedOperationException( "No drop schema syntax supported by " + getClass().getName() );
	}

	@Override
	public boolean qualifyIndexName() {
		return false;

	}

	@Override
	public boolean supportsCommentOn() {
		return getVersion() >= 200;
	}

	@Override
	public boolean supportsEmptyInList() {
		return false;
	}

	@Override
	public boolean requiresCastingOfParametersInSelectClause() {
		return true;
	}

	@Override
	public boolean supportsLobValueChangePropagation() {
		// May need changes in Jaybird for this to work
		return false;
	}

	@Override
	public boolean supportsUnboundedLobLocatorMaterialization() {
		// Blob ids are only guaranteed to work in the same transaction
		return false;
	}

	@Override
	public boolean supportsTupleDistinctCounts() {
		return false;
	}

	@Override
	public int getInExpressionCountLimit() {
		// see https://www.firebirdsql.org/file/documentation/html/en/refdocs/fblangref25/firebird-25-language-reference.html#fblangref25-commons-in
		return 1500;
	}

	@Override
	public boolean supportsTuplesInSubqueries() {
		return false;
	}

	@Override
	public boolean supportsExistsInSelect() {
		return getVersion() >= 300;
	}

	@Override
	public boolean supportsPartitionBy() {
		return getVersion() >= 300;
	}

	@Override
	public String toBooleanValueString(boolean bool) {
		//'boolean' type introduced in 3.0
		return getVersion() < 300
				? super.toBooleanValueString( bool )
				: bool ? "true" : "false";
	}

	@Override
	public IdentityColumnSupport getIdentityColumnSupport() {
		return getVersion() < 300
				? super.getIdentityColumnSupport()
				: new FirebirdIdentityColumnSupport();
	}

	@Override
	public SequenceSupport getSequenceSupport() {
		if ( getVersion() < 200 ) {
			return InterbaseSequenceSupport.INSTANCE;
		}
		else if ( getVersion() < 300 ) {
			return FirebirdSequenceSupport.LEGACY_INSTANCE;
		}
		else {
			return FirebirdSequenceSupport.INSTANCE;
		}
	}

	@Override
	public String getQuerySequencesString() {
		return getVersion() < 300
				? "select rdb$generator_name from rdb$generators"
				// Note: Firebird 3 has an 'off by increment' bug (fixed in Firebird 4), see
				// http://tracker.firebirdsql.org/browse/CORE-6084
				: "select rdb$generator_name, rdb$initial_value, rdb$generator_increment from rdb$generators where coalesce(rdb$system_flag, 0) = 0";
	}

	@Override
	public SequenceInformationExtractor getSequenceInformationExtractor() {
		return getVersion() < 300
				? SequenceNameExtractorImpl.INSTANCE
				: SequenceInformationExtractorFirebirdDatabaseImpl.INSTANCE;
	}

	@Override
	public String getForUpdateString() {
		// locking only happens on fetch
		// ('for update' would force Firebird to return a single row per fetch)
		return " with lock";
	}

	@Override
	public LimitHandler getLimitHandler() {
		return getVersion() < 300
				? SkipFirstLimitHandler.INSTANCE
				: OffsetFetchLimitHandler.INSTANCE;
	}

	@Override
	public String getSelectGUIDString() {
		return getVersion() < 210
				? super.getSelectGUIDString()
				: "select uuid_to_char(gen_uuid()) from rdb$database";
	}

	@Override
	public boolean supportsLockTimeouts() {
		// Lock timeouts are only supported when specified as part of the transaction
		return false;
	}

	@Override
	public boolean supportsOuterJoinForUpdate() {
		// "WITH LOCK can only be used with a top-level, single-table SELECT statement"
		// https://www.firebirdsql.org/file/documentation/reference_manuals/fblangref25-en/html/fblangref25-dml-select.html#fblangref25-dml-with-lock
		return false;
	}

	@Override
	public boolean supportsCurrentTimestampSelection() {
		return true;
	}

	@Override
	public String getCurrentTimestampSelectString() {
		return "select current_timestamp from rdb$database";
	}

	@Override
	public boolean isCurrentTimestampSelectStringCallable() {
		return false;
	}

	@Override
	public String getFromDual() {
		return "from rdb$database";
	}

	@Override
	public boolean supportsSelectQueryWithoutFromClause() {
		return false;
	}

	@Override
	public NullOrdering getNullOrdering() {
		return getVersion() >= 200 ? NullOrdering.SMALLEST : NullOrdering.LAST;
	}

	@Override
	public boolean supportsNullPrecedence() {
		return getVersion() >= 150;
	}

	@Override
	public boolean supportsOffsetInSubquery() {
		return true;
	}

	@Override
	public boolean supportsValuesListForInsert() {
		return false;
	}

	@Override
	public boolean supportsWindowFunctions() {
		return getVersion() >= 300;
	}

	@Override
	public String translateExtractField(TemporalUnit unit) {
		switch ( unit ) {
			case DAY_OF_MONTH: return "day";
			case DAY_OF_YEAR: return "yearday";
			case DAY_OF_WEEK: return "weekday";
			default: return super.translateExtractField( unit );
		}
	}

	@Override
	protected String formatAsTimestamp(Date date, TimeZone jdbcTimeZone) {
		return formatAsTimestampWithMillis( date, jdbcTimeZone );
	}

	@Override
	protected String formatAsTimestamp(Calendar calendar, TimeZone jdbcTimeZone) {
		return formatAsTimestampWithMillis( calendar, jdbcTimeZone );
	}

	@Override
	protected String formatAsTimestamp(TemporalAccessor temporalAccessor, TimeZone jdbcTimeZone) {
		return formatAsTimestampWithMillis( temporalAccessor, supportsTemporalLiteralOffset(), jdbcTimeZone );
	}

	@Override
	public String translateDatetimeFormat(String format) {
		throw new NotYetImplementedFor6Exception( "format() function not supported on Firebird" );
	}

	@Override
	public ViolatedConstraintNameExtractor getViolatedConstraintNameExtractor() {
		return EXTRACTOR;
	}

	private static final Pattern FOREIGN_UNIQUE_OR_PRIMARY_KEY_PATTERN =
			Pattern.compile( "violation of .+? constraint \"([^\"]+)\"" );
	private static final Pattern CHECK_CONSTRAINT_PATTERN =
			Pattern.compile( "Operation violates CHECK constraint (.+?) on view or table" );

	private static final ViolatedConstraintNameExtractor EXTRACTOR = sqle -> {
		String message = sqle.getMessage();
		if ( message != null ) {
			Matcher foreignUniqueOrPrimaryKeyMatcher =
					FOREIGN_UNIQUE_OR_PRIMARY_KEY_PATTERN.matcher( message );
			if ( foreignUniqueOrPrimaryKeyMatcher.find() ) {
				return foreignUniqueOrPrimaryKeyMatcher.group( 1 );
			}

			Matcher checkConstraintMatcher = CHECK_CONSTRAINT_PATTERN.matcher( message );
			if ( checkConstraintMatcher.find() ) {
				return checkConstraintMatcher.group( 1 );
			}
		}
		return null;
	};

	@Override
	public SQLExceptionConversionDelegate buildSQLExceptionConversionDelegate() {
		return (sqlException, message, sql) -> {
			final int errorCode = JdbcExceptionHelper.extractErrorCode( sqlException );
			final String sqlExceptionMessage = sqlException.getMessage();
			//final String sqlState = JdbcExceptionHelper.extractSqlState( sqlException );

			// Some of the error codes will only surface in Jaybird 3 or higher, as older versions return less specific error codes first
			switch ( errorCode ) {
				case 335544336:
					// isc_deadlock (deadlock, note: not necessarily a deadlock, can also be an update conflict)
					if ( sqlExceptionMessage != null
							&& sqlExceptionMessage.contains( "update conflicts with concurrent update" ) ) {
						return new LockTimeoutException( message, sqlException, sql );
					}
					return new LockAcquisitionException( message, sqlException, sql );
				case 335544345:
					// isc_lock_conflict (lock conflict on no wait transaction)
				case 335544510:
					// isc_lock_timeout (lock time-out on wait transaction)
					return new LockTimeoutException( message, sqlException, sql );
				case 335544474:
					// isc_bad_lock_level (invalid lock level {0})
				case 335544475:
					// isc_relation_lock (lock on table {0} conflicts with existing lock)
				case 335544476:
					// isc_record_lock (requested record lock conflicts with existing lock)
					return new LockAcquisitionException( message, sqlException, sql );
				case 335544466:
					// isc_foreign_key (violation of FOREIGN KEY constraint "{0}" on table "{1}")
				case 336396758:
					// *no error name* (violation of FOREIGN KEY constraint "{0}")
				case 335544558:
					// isc_check_constraint (Operation violates CHECK constraint {0} on view or table {1})
				case 336396991:
					// *no error name* (Operation violates CHECK constraint {0} on view or table)
				case 335544665:
					// isc_unique_key_violation (violation of PRIMARY or UNIQUE KEY constraint "{0}" on table "{1}")
					final String constraintName = getViolatedConstraintNameExtractor().extractConstraintName(
							sqlException );
					return new ConstraintViolationException( message, sqlException, sql, constraintName );
			}

			// Apply heuristics based on exception message
			String exceptionMessage = sqlException.getMessage();
			if ( exceptionMessage != null ) {
				if ( exceptionMessage.contains( "violation of " )
						|| exceptionMessage.contains( "violates CHECK constraint" ) ) {
					final String constraintName = getViolatedConstraintNameExtractor().extractConstraintName(
							sqlException );
					return new ConstraintViolationException( message, sqlException, sql, constraintName );
				}
			}

			return null;
		};
	}

	@Override
	public SqmMultiTableMutationStrategy getFallbackSqmMutationStrategy(EntityMappingType entityDescriptor, RuntimeModelCreationContext runtimeModelCreationContext) {
		return getVersion() < 210
				? super.getFallbackSqmMutationStrategy( entityDescriptor, runtimeModelCreationContext )
				: new GlobalTemporaryTableStrategy(
						new IdTable( entityDescriptor, name -> "HT_" + name, this ),
						() -> new TempIdTableExporter( false, this::getTypeName ) {
							@Override
							protected String getCreateOptions() {
								return "on commit delete rows";
							}
						},
						AfterUseAction.CLEAN,
						runtimeModelCreationContext.getSessionFactory()
				);
	}
}