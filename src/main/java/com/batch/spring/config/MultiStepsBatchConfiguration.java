package com.batch.spring.config;

import java.io.FileNotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.batch.item.file.transform.DelimitedLineAggregator;
import org.springframework.batch.item.file.transform.FieldExtractor;
import org.springframework.batch.item.validator.ValidatingItemProcessor;
import org.springframework.batch.item.validator.ValidationException;
import org.springframework.batch.item.xml.builder.StaxEventItemWriterBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.oxm.Marshaller;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;

import com.batch.spring.batch.exceptions.MinPriceValidationException;
import com.batch.spring.batch.extensions.TransactionItemReadListener;
import com.batch.spring.batch.extensions.TransactionItemSkipListerner;
import com.batch.spring.batch.extensions.TransactionJDBCItemWriteListener;
import com.batch.spring.batch.extensions.TransactionRowMapper;
import com.batch.spring.batch.validators.TransactionJDBCValidator;
import com.batch.spring.batch.validators.TransactionValidator;
import com.batch.spring.model.Transaction;
import com.batch.spring.multisteps.config.CSVToXMLBatchJobMetaDataConfigProperties;
import com.batch.spring.multisteps.config.TransactionXMLMetadataConfiguration;

/**
 * Configuration for Spring batch supporting multiple steps
 * 
 * We are not configuring a separate JobRepository here. Using the default
 * configured by @EnableBatchProcessing
 * 
 * @author prashantsingh
 *
 */

@Configuration
@Profile({ "steps", "csv-xml" })

public class MultiStepsBatchConfiguration {

	private static final Logger logger = LoggerFactory.getLogger(MultiStepsBatchConfiguration.class);
	
	
	@Bean
	public JdbcTemplate jdbcTemplate(DataSource dataSource) {
		return new JdbcTemplate(dataSource);
	}

	/**
	 * Declaring a Configuration Scope
	 * 
	 * This scope has beans defined for DB to XML Configuration Step
	 * 
	 * @author prashantsingh
	 *
	 */

	@Configuration
	@Profile({ "steps", "csv-xml" })
	public static class DBtoXMLBatchStepConfiguration {

		@Value("${spring-batch.single.output.location.pathxml}")
		private FileSystemResource outputResource;

		@Autowired
		private CSVToXMLBatchJobMetaDataConfigProperties batchMetaData;

		@Bean
		public Marshaller marshaller() {
			Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
			marshaller.setClassesToBeBound(new Class[] { Transaction.class });
			return marshaller;
		}

		// Adding an XML Writer
		@Bean
		ItemWriter<Transaction> xmlItemWriter(TransactionXMLMetadataConfiguration tConfiguration) {
			return new StaxEventItemWriterBuilder<Transaction>().name(batchMetaData.getXmlWriterStepName())
					.resource(outputResource).marshaller(marshaller()).rootTagName(tConfiguration.getRootTagName())
					.build();

		}

		@Bean
		ItemProcessor<Transaction, Transaction> jdbcItemProcessor() {
			return new ValidatingItemProcessor<Transaction>(new TransactionJDBCValidator());
		}

		@Bean
		ItemReader<Transaction> jdbcItemReader(DataSource dataSource) {
			return new JdbcCursorItemReaderBuilder<Transaction>().sql(Transaction.SELECT_TRANSACTION_SQL)
					.name(batchMetaData.getJdbcReaderStepName()).dataSource(dataSource)
					.rowMapper(new TransactionRowMapper()).build();
		}

	}

	/**
	 * Defining Configuration for CSV to Batch Step Batch Job
	 * 
	 * @author prashantsingh
	 *
	 */

	@Configuration
	@Profile({ "steps", "csv-xml" })
	public static class CSVtoDBBatchStepConfiguration {

		@Autowired
		private CSVToXMLBatchJobMetaDataConfigProperties batchMetaData;

		/**
		 * Defining an input resource. From this location, batch will try to load the
		 * CSV. Configure path for the CSV file in application.properties.
		 * 
		 */

		@Value("${spring-batch.single.input.resource.path}")
		private FileSystemResource inputCsv;

		/**
		 * Registering a FlatFileItemReaderBuiler as ItemReader.
		 * 
		 * @return
		 */

		@Bean
		ItemReader<Transaction> csvItemReader() {
			return new FlatFileItemReaderBuilder<Transaction>().name(batchMetaData.getCsvReaderStepName())
					.resource(inputCsv).strict(true).targetType(Transaction.class).delimited().delimiter(",")
					.names(Transaction.FIELDS_METADATA).build();
		}

		@Bean
		ItemProcessor<Transaction, Transaction> itemProcessor() {
			ValidatingItemProcessor<Transaction> validatingTransaction = new ValidatingItemProcessor<Transaction>(
					new TransactionValidator());
			validatingTransaction.setFilter(true);
			return validatingTransaction;
		}

		/**
		 * Registering a JdbcBatchItemWriter to write the read items into data source
		 * configured
		 * 
		 * @param dataSource
		 * @return
		 */
		@Bean
		ItemWriter<Transaction> jdbcItemWriter(DataSource dataSource) {
			return new JdbcBatchItemWriterBuilder<Transaction>().dataSource(dataSource)
					.sql(Transaction.INSERT_TRANSACTION_SQL).beanMapped().build();

		}
	}

	/**
	 * A Spring Configuration : Used for Scoping purpose
	 * 
	 * @author prashantsingh
	 *
	 */
	@Configuration
	@Profile("steps")
	public static class DBtoCSVBatchConfiguration {

		@Value("${spring-batch.single.output.resource.path}")
		private FileSystemResource csvOutput;

		@Bean
		ItemReader<Map<Integer, String>> jdbcItemReader(DataSource dataSource) {
			return new JdbcCursorItemReaderBuilder<Map<Integer, String>>()
					.sql(Transaction.SELECT_TRANSACTION_GROUPED_BY_SQL).name("jdbc-raeader").dataSource(dataSource)
					.rowMapper(new RowMapper<Map<Integer, String>>() {
						@Override
						public Map<Integer, String> mapRow(ResultSet rs, int rowNum) throws SQLException {
							return Collections.singletonMap(rs.getInt(1), rs.getString(2));
						}
					}).build();
		}

		@Bean
		ItemWriter<Map<Integer, String>> csvItemWriter() {
			return new FlatFileItemWriterBuilder<Map<Integer, String>>().name("csv-to-db-writer").resource(csvOutput)
					.lineAggregator(new DelimitedLineAggregator<Map<Integer, String>>() {
						{
							setDelimiter(",");
							setFieldExtractor(new FieldExtractor<Map<Integer, String>>() {
								public Object[] extract(Map<Integer, String> entry) {
									Map.Entry<Integer, String> mapEntry = entry.entrySet().iterator().next();
									return new Object[] { mapEntry.getKey(), mapEntry.getValue() };
								}
							});
						}
					}).build();
		}

	}

	/**
	 * Registering a Job( Actual runnable job) in to the context. If Spring finds
	 * any job registered, is starts them.
	 * 
	 * @param dataSource                 Data source configured by Spring Boot
	 *                                   Default Configuration
	 * @param stepBuilderFactory         StepBuilderFactory : used to build Steps
	 *                                   for Job
	 * @param jobBuilderFactory          JobBuilderFactory : used to build Jobs
	 * @param csvToDbConfiguration       A Configuration Object
	 * @param dbBtoCSVBatchConfiguration A Configuration Object
	 * @return Job instance
	 */
	@Bean
	@Profile("steps")
	Job job(DataSource dataSource, StepBuilderFactory stepBuilderFactory, JobBuilderFactory jobBuilderFactory,
			CSVtoDBBatchStepConfiguration csvToDbConfiguration, DBtoCSVBatchConfiguration dbBtoCSVBatchConfiguration,
			CSVToXMLBatchJobMetaDataConfigProperties batchJobMetaData) {

		logger.info("Started processing of job ");

		Step csvToDbStep = stepBuilderFactory.get(batchJobMetaData.getCsvReaderStepName())
				.<Transaction, Transaction>chunk(batchJobMetaData.getCsvReadChunkSize())
				.reader(csvToDbConfiguration.csvItemReader()).processor(csvToDbConfiguration.itemProcessor())
				.writer(csvToDbConfiguration.jdbcItemWriter(dataSource)).listener(new TransactionItemReadListener())
				.faultTolerant().skip(FlatFileParseException.class)
				.noSkip(FileNotFoundException.class).skipLimit(batchJobMetaData.getCsvReadSkipLimit()).build();

		Step dbToCSVStep = stepBuilderFactory.get("db-to-csv")
				.<Map<Integer, String>, Map<Integer, String>>chunk(batchJobMetaData.getJdbcReadChunkSize())
				.reader(dbBtoCSVBatchConfiguration.jdbcItemReader(dataSource))
				.writer(dbBtoCSVBatchConfiguration.csvItemWriter()).build();

		return jobBuilderFactory.get(batchJobMetaData.getBatchName()).incrementer(new RunIdIncrementer())
				.start(csvToDbStep).next(dbToCSVStep).build();

	}

	/**
	 * 
	 * @param dataSource                    Data source configured by Spring Boot
	 *                                      Default Configuration
	 * @param stepBuilderFactory            StepBuilderFactory : used to build Steps
	 *                                      for Job
	 * @param jobBuilderFactory             JobBuilderFactory : used to build Jobs
	 * @param csvToDbConfiguration          CSVtoDBBatchStepConfiguration CSV-DB
	 *                                      Batch Beans Definitions
	 * @param dBtoXMLBatchStepConfiguration DBtoXMLBatchStepConfiguration DB-XML
	 *                                      Batch beans Definitions
	 * @param batchJobMetaData              CSVToXMLBatchJobMetaDataConfigProperties
	 *                                      Metadata Configuration
	 * @return
	 * @author prashantsingh
	 */
	@Bean
	@Profile("csv-xml")
	Job csvXmlJob(DataSource dataSource, StepBuilderFactory stepBuilderFactory, JobBuilderFactory jobBuilderFactory,
			CSVtoDBBatchStepConfiguration csvToDbConfiguration,
			DBtoXMLBatchStepConfiguration dBtoXMLBatchStepConfiguration,
			CSVToXMLBatchJobMetaDataConfigProperties batchJobMetaData,
			TransactionItemReadListener itemReadListener) {

		logger.info("Started processing of csvXmlJob ");
		Step csvToDbStep = stepBuilderFactory.get(batchJobMetaData.getCsvReaderStepName())
				.<Transaction, Transaction>chunk(batchJobMetaData.getCsvReadChunkSize())
				.reader(csvToDbConfiguration.csvItemReader()).processor(csvToDbConfiguration.itemProcessor())
				.writer(csvToDbConfiguration.jdbcItemWriter(dataSource)).listener(itemReadListener)
				.faultTolerant().skip(FlatFileParseException.class).skip(SQLException.class)
				.listener(new TransactionItemSkipListerner())
				
				.noSkip(FileNotFoundException.class).skipLimit(batchJobMetaData.getCsvReadSkipLimit()).build();

		Step dbToXMLStep = stepBuilderFactory.get(batchJobMetaData.getJdbcReaderStepName())
				.<Transaction, Transaction>chunk(batchJobMetaData.getJdbcReadChunkSize())
				.reader(dBtoXMLBatchStepConfiguration.jdbcItemReader(dataSource))
				.processor(dBtoXMLBatchStepConfiguration.jdbcItemProcessor())
				.writer(dBtoXMLBatchStepConfiguration.xmlItemWriter(null))
				.listener(new TransactionJDBCItemWriteListener()).faultTolerant()
				.listener(new TransactionItemSkipListerner()).skipLimit(batchJobMetaData.getJdbcReadSkipLimit())
				.noRollback(ValidationException.class).noRollback(MinPriceValidationException.class)
				.skip(MinPriceValidationException.class).build();

		logger.info(" Both Steps configured properly ");

		return jobBuilderFactory.get(batchJobMetaData.getBatchName()).incrementer(new RunIdIncrementer())
				.start(csvToDbStep).next(dbToXMLStep).build();

	}

}
