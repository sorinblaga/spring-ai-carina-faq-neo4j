package com.example.carina.data;

import org.neo4j.cypherdsl.support.schema_name.SchemaNames;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.beans.factory.InitializingBean;

import java.util.Map;

@Service
public class DataService implements InitializingBean {
    private static final Logger logger = LoggerFactory.getLogger(DataService.class);

    @Value("classpath:/data/medicaid-wa-faqs.pdf")
    private Resource pdfResource;

    @Value("${spring.ai.vectorstore.neo4j.label}")
    private String vectorStoreLabel;

    private String vectorStoreQuotedLabel;

    private final VectorStore vectorStore;

    private final Driver driver;

    @Autowired
    public DataService(VectorStore vectorStore, Driver driver) {
        Assert.notNull(vectorStore, "VectorStore must not be null.");
        this.vectorStore = vectorStore;
        this.driver = driver;
    }

    public void load() {
        PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(
                this.pdfResource,
                PdfDocumentReaderConfig.builder()
                        .withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
                                .withNumberOfBottomTextLinesToDelete(3)
                                .withNumberOfTopPagesToSkipBeforeDelete(1)
                                // .withLeftAlignment(true)
                                .build())
                        .withPagesPerDocument(1)
                        .build());

        var textSplitter = new TokenTextSplitter();

        logger.info("Parsing document, splitting, creating embeddings and storing in vector store...  this will take a while.");
        this.vectorStore.accept(
                textSplitter.apply(
                        pdfReader.get())
                        .stream()
                        .peek(document -> document.getMetadata().clear())
                        .toList()
        );
        logger.info("Done parsing document, splitting, creating embeddings and storing in vector store");

    }

    public int count(){
        try (var session = this.driver.session()) {
            Result result = session.run("""
					MATCH (n:%s)
					RETURN count(n) as count
					 """.formatted(this.vectorStoreQuotedLabel));
            if (result.hasNext()) {
                return result.next().get("count").asInt();
            }
            return 0;
        }
    }

    public void delete(){
        try (var session = this.driver.session()) {
            session.run("""
					MATCH (n:%s)
					CALL { WITH n DETACH DELETE n } IN TRANSACTIONS OF $transactionSize ROWS
					 """.formatted(this.vectorStoreQuotedLabel), Map.of( "transactionSize", 10_000))
                    .consume();
        }
    }

    @Override
    public void afterPropertiesSet() {
        this.vectorStoreQuotedLabel = SchemaNames.sanitize(this.vectorStoreLabel).orElseThrow();
    }
}
