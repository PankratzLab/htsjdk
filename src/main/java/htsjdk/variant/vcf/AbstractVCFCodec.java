/*
* Copyright (c) 2012 The Broad Institute
* 
* Permission is hereby granted, free of charge, to any person
* obtaining a copy of this software and associated documentation
* files (the "Software"), to deal in the Software without
* restriction, including without limitation the rights to use,
* copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the
* Software is furnished to do so, subject to the following
* conditions:
* 
* The above copyright notice and this permission notice shall be
* included in all copies or substantial portions of the Software.
* 
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
* OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
* NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
* HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
* WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
* FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
* THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

package htsjdk.variant.vcf;

import htsjdk.samtools.util.BlockCompressedInputStream;
import htsjdk.samtools.util.IOUtil;
import htsjdk.tribble.AsciiFeatureCodec;
import htsjdk.tribble.Feature;
import htsjdk.tribble.NameAwareCodec;
import htsjdk.tribble.TribbleException;
import htsjdk.tribble.index.tabix.TabixFormat;
import htsjdk.tribble.util.ParsingUtils;
import htsjdk.utils.ValidationUtils;
import htsjdk.variant.utils.GeneralUtils;
import htsjdk.variant.variantcontext.*;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

public abstract class AbstractVCFCodec extends AsciiFeatureCodec<VariantContext> implements NameAwareCodec {
    public final static int MAX_ALLELE_SIZE_BEFORE_WARNING = (int)Math.pow(2, 20);

    protected final static int NUM_STANDARD_FIELDS = 8;  // INFO is the 8th

    // we have to store the list of strings that make up the header until they're needed
    protected VCFHeader header = null;
    protected VCFHeaderVersion version = null;

    private final static VCFTextTransformer percentEncodingTextTransformer = new VCFPercentEncodedTextTransformer();
    private final static VCFTextTransformer passThruTextTransformer = new VCFPassThruTextTransformer();
    //by default, we use the passThruTextTransformer (assume pre v4.3)
    private VCFTextTransformer vcfTextTransformer = passThruTextTransformer;

    // a mapping of the allele
    protected ThreadLocal<Map<String, List<Allele>>> alleleMap =
            ThreadLocal.withInitial(() -> new HashMap<String, List<Allele>>(3));

    // for performance testing purposes
    public static boolean validate = true;

    // a key optimization -- we need a per thread string parts array, so we don't allocate a big
    // array
    // over and over
    protected ThreadLocal<String[]> parts = ThreadLocal.withInitial(() -> null);
    protected ThreadLocal<String[]> genotypeParts = ThreadLocal.withInitial(() -> null);

    // for performance we cache the hashmap of filter encodings for quick lookup
    protected HashMap<String, List<String>> filterHash = new HashMap<String, List<String>>();

    // we store a name to give to each of the variant contexts we emit
    protected String name = "Unknown";

    protected AtomicInteger lineNo = new AtomicInteger(0);

    protected Map<String, String> stringCache = new HashMap<String, String>();

    protected boolean warnedAboutNoEqualsForNonFlag = false;

    /**
     * If true, then we'll magically fix up VCF headers on the fly when we read them in
     */
    protected boolean doOnTheFlyModifications = true;

    /**
     * If non-null, we will replace the sample name read from the VCF header with this sample name. This feature works
     * only for single-sample VCFs.
     */
    protected String remappedSampleName = null;

    protected AbstractVCFCodec() {
        super(VariantContext.class);
    }

    /**
     * Creates a LazyParser for a LazyGenotypesContext to use to decode
     * our genotypes only when necessary.  We do this instead of eagarly
     * decoding the genotypes just to turn around and reencode in the frequent
     * case where we don't actually want to manipulate the genotypes
     */
    class LazyVCFGenotypesParser implements LazyGenotypesContext.LazyParser {
        final List<Allele> alleles;
        final String contig;
        final int start;
        final int lineNum;

        LazyVCFGenotypesParser(final List<Allele> alleles, final String contig, final int start, final int lineNum) {
            this.alleles = alleles;
            this.contig = contig;
            this.start = start;
            this.lineNum = lineNum;
        }

        @Override
        public LazyGenotypesContext.LazyData parse(final Object data) {
            //System.out.printf("Loading genotypes... %s:%d%n", contig, start);
            return createGenotypeMap((String) data, alleles, contig, start, lineNum);
        }
    }

    /**
     * parse the filter string, first checking to see if we already have parsed it in a previous attempt
     * @param filterString the string to parse
     * @return a set of the filters applied
     */
    protected abstract List<String> parseFilters(String filterString);

    /**
     * create a VCF header from a set of header record lines
     *
     * @param headerStrings a list of strings that represent all the ## and # entries
     * @return a VCFHeader object
     */
    protected VCFHeader parseHeaderFromLines( final List<String> headerStrings, final VCFHeaderVersion version ) {
        this.version = version;

        Set<VCFHeaderLine> metaData = new LinkedHashSet<VCFHeaderLine>();
        Set<String> sampleNames = new LinkedHashSet<String>();
        int contigCounter = 0;
        // iterate over all the passed in strings
        for ( String str : headerStrings ) {
            if ( !str.startsWith(VCFHeader.METADATA_INDICATOR) ) {
                String[] strings = str.substring(1).split(VCFConstants.FIELD_SEPARATOR);
                if ( strings.length < VCFHeader.HEADER_FIELDS.values().length )
                    throw new TribbleException.InvalidHeader("there are not enough columns present in the header line: " + str);

                int arrayIndex = 0;
                for (VCFHeader.HEADER_FIELDS field : VCFHeader.HEADER_FIELDS.values()) {
                    try {
                        if (field != VCFHeader.HEADER_FIELDS.valueOf(strings[arrayIndex]))
                            throw new TribbleException.InvalidHeader("we were expecting column name '" + field + "' but we saw '" + strings[arrayIndex] + "'");
                    } catch (IllegalArgumentException e) {
                        throw new TribbleException.InvalidHeader("unknown column name '" + strings[arrayIndex] + "'; it does not match a legal column header name.");
                    }
                    arrayIndex++;
                }

                boolean sawFormatTag = false;
                if ( arrayIndex < strings.length ) {
                    if ( !strings[arrayIndex].equals("FORMAT") )
                        throw new TribbleException.InvalidHeader("we were expecting column name 'FORMAT' but we saw '" + strings[arrayIndex] + "'");
                    sawFormatTag = true;
                    arrayIndex++;
                }

                while ( arrayIndex < strings.length )
                    sampleNames.add(strings[arrayIndex++]);

                if ( sawFormatTag && sampleNames.isEmpty())
                    throw new TribbleException.InvalidHeader("The FORMAT field was provided but there is no genotype/sample data");

                // If we're performing sample name remapping and there is exactly one sample specified in the header, replace
                // it with the remappedSampleName. Throw an error if there are 0 or multiple samples and remapping was requested
                // for this file.
                if ( remappedSampleName != null ) {
                    // We currently only support on-the-fly sample name remapping for single-sample VCFs
                    if ( sampleNames.isEmpty() || sampleNames.size() > 1 ) {
                        throw new TribbleException(String.format("Cannot remap sample name to %s because %s samples are specified in the VCF header, and on-the-fly sample name remapping is only supported for single-sample VCFs",
                                                                 remappedSampleName, sampleNames.isEmpty() ? "no" : "multiple"));
                    }

                    sampleNames.clear();
                    sampleNames.add(remappedSampleName);
                }

            } else {
                if ( str.startsWith(VCFConstants.INFO_HEADER_START) ) {
                    final VCFInfoHeaderLine info = new VCFInfoHeaderLine(str.substring(7), version);
                    metaData.add(info);
                } else if ( str.startsWith(VCFConstants.FILTER_HEADER_START) ) {
                    final VCFFilterHeaderLine filter = new VCFFilterHeaderLine(str.substring(9), version);
                    metaData.add(filter);
                } else if ( str.startsWith(VCFConstants.FORMAT_HEADER_START) ) {
                    final VCFFormatHeaderLine format = new VCFFormatHeaderLine(str.substring(9), version);
                    metaData.add(format);
                } else if ( str.startsWith(VCFConstants.CONTIG_HEADER_START) ) {
                    final VCFContigHeaderLine contig = new VCFContigHeaderLine(str.substring(9), version, VCFConstants.CONTIG_HEADER_START.substring(2), contigCounter++);
                    metaData.add(contig);
                } else if ( str.startsWith(VCFConstants.ALT_HEADER_START) ) {
                    metaData.add(getAltHeaderLine(str.substring(VCFConstants.ALT_HEADER_OFFSET), version));
                } else if ( str.startsWith(VCFConstants.PEDIGREE_HEADER_START) && version.isAtLeastAsRecentAs(VCFHeaderVersion.VCF4_3)) {
                    // only model pedigree header lines as structured header lines starting with v4.3
                    metaData.add(getPedigreeHeaderLine(str.substring(VCFConstants.PEDIGREE_HEADER_OFFSET), version));
                } else if ( str.startsWith(VCFConstants.META_HEADER_START) ) {
                    metaData.add(getMetaHeaderLine(str.substring(VCFConstants.META_HEADER_OFFSET), version));
                } else if ( str.startsWith(VCFConstants.SAMPLE_HEADER_START) ) {
                    metaData.add(getSampleHeaderLine(str.substring(VCFConstants.SAMPLE_HEADER_OFFSET), version));
                } else {
                    int equals = str.indexOf('=');
                    if ( equals != -1 )
                        metaData.add(new VCFHeaderLine(str.substring(2, equals), str.substring(equals+1)));
                }
            }
        }

        setVCFHeader(new VCFHeader(version, metaData, sampleNames), version);
        return this.header;
    }

    /**
     * @return the header that was either explicitly set on this codec, or read from the file. May be null.
     * The returned value should not be modified.
     */
    public VCFHeader getHeader() {
        return header;
    }

    /**
     * @return the version number that was either explicitly set on this codec, or read from the file. May be null.
     */
    public VCFHeaderVersion getVersion() {
        return version;
    }

    /**
     * Explicitly set the VCFHeader on this codec. This will overwrite the header read from the file
     * and the version state stored in this instance; conversely, reading the header from a file will
     * overwrite whatever is set here.
     *
     * @param newHeader
     * @param newVersion
     * @return the actual header for this codec. The returned header may not be identical to the header
     * argument since the header lines may be "repaired" (i.e., rewritten) if doOnTheFlyModifications is set.
     * @throws TribbleException if the requested header version is not compatible with the existing version
     */
    public VCFHeader setVCFHeader(final VCFHeader newHeader, final VCFHeaderVersion newVersion) {
        validateHeaderVersionTransition(newHeader, newVersion);
        if (this.doOnTheFlyModifications) {
            final VCFHeader repairedHeader = VCFStandardHeaderLines.repairStandardHeaderLines(newHeader);
            // validate the new header after repair to ensure the resulting header version is
            // still compatible with the current version
            validateHeaderVersionTransition(repairedHeader, newVersion);
            this.header = repairedHeader;
        } else {
            this.header = newHeader;
        }

        this.version = newVersion;
        this.vcfTextTransformer = getTextTransformerForVCFVersion(newVersion);

        return this.header;
    }

    /**
     * Create and return a VCFAltHeaderLine object from a header line string that conforms to the {@code sourceVersion}
     * @param headerLineString VCF header line being parsed without the leading "##ALT="
     * @param sourceVersion the VCF header version derived from which the source was retrieved. The resulting header
     *                      line object should be validate for this header version.
     * @return a VCFAltHeaderLine object
     */
    public VCFAltHeaderLine getAltHeaderLine(final String headerLineString, final VCFHeaderVersion sourceVersion) {
        return new VCFAltHeaderLine(headerLineString, sourceVersion);
    }

    /**
     * Create and return a VCFPedigreeHeaderLine object from a header line string that conforms to the {@code sourceVersion}
     * @param headerLineString VCF header line being parsed without the leading "##PEDIGREE="
     * @param sourceVersion the VCF header version derived from which the source was retrieved. The resulting header
     *                      line object should be validate for this header version.
     * @return a VCFPedigreeHeaderLine object
     */
    public VCFPedigreeHeaderLine getPedigreeHeaderLine(final String headerLineString, final VCFHeaderVersion sourceVersion) {
        return new VCFPedigreeHeaderLine(headerLineString, sourceVersion);
    }

    /**
     * Create and return a VCFMetaHeaderLine object from a header line string that conforms to the {@code sourceVersion}
     * @param headerLineString VCF header line being parsed without the leading "##META="
     * @param sourceVersion the VCF header version derived from which the source was retrieved. The resulting header
     *                      line object should be validate for this header version.
     * @return a VCFMetaHeaderLine object
     */
    public VCFMetaHeaderLine getMetaHeaderLine(final String headerLineString, final VCFHeaderVersion sourceVersion) {
        return new VCFMetaHeaderLine(headerLineString, sourceVersion);
    }

    /**
     * Create and return a VCFSampleHeaderLine object from a header line string that conforms to the {@code sourceVersion}
     * @param headerLineString VCF header line being parsed without the leading "##SAMPLE="
     * @param sourceVersion the VCF header version derived from which the source was retrieved. The resulting header
     *                      line object should be validate for this header version.
     * @return a VCFSampleHeaderLine object
     */
    public VCFSampleHeaderLine getSampleHeaderLine(final String headerLineString, final VCFHeaderVersion sourceVersion) {
        return new VCFSampleHeaderLine(headerLineString, sourceVersion);
    }

    /**
     * the fast decode function
     * @param line the line of text for the record
     * @return a feature, (not guaranteed complete) that has the correct start and stop
     */
    public Feature decodeLoc(String line) {
        return decodeLine(line, false, lineNo.getAndIncrement());
    }

    /**
     * decode the line into a feature (VariantContext)
     * @param line the line
     * @return a VariantContext
     */
    @Override
    public VariantContext decode(String line) {
        return decodeLine(line, true, lineNo.getAndIncrement());
    }

    /**
     * Throw if new a version/header are not compatible with the existing version/header. Generally, any version
     * before v4.2 can be up-converted to v4.2, but not to v4.3. Once a header is established as v4.3, it cannot
     * can not be up or down converted, and it must remain at v4.3.
     * @param newHeader
     * @param newVersion
     * @throws TribbleException if the header conversion is not valid
     */
    private void validateHeaderVersionTransition(final VCFHeader newHeader, final VCFHeaderVersion newVersion) {
        ValidationUtils.nonNull(newHeader);
        ValidationUtils.nonNull(newVersion);

        VCFHeader.validateVersionTransition(version, newVersion);

        // If this codec currently has no header (this happens when the header is being established for
        // the first time during file parsing), establish an initial header and version, and bypass
        // validation.
        if (header != null && newHeader.getVCFHeaderVersion() != null) {
            VCFHeader.validateVersionTransition(header.getVCFHeaderVersion(), newHeader.getVCFHeaderVersion());
        }
    }

    /**
     * For v4.3 up, attribute values can contain embedded percent-encoded characters which must be decoded
     * on read. Return a version-aware text transformer that can decode encoded text.
     * @param targetVersion the version for which a transformer is bing requested
     * @return a {@link VCFTextTransformer} suitable for the targetVersion
     */
    private VCFTextTransformer getTextTransformerForVCFVersion(final VCFHeaderVersion targetVersion) {
        return targetVersion != null && targetVersion.isAtLeastAsRecentAs(VCFHeaderVersion.VCF4_3) ?
                percentEncodingTextTransformer :
                passThruTextTransformer;
    }

    private VariantContext decodeLine(final String line, final boolean includeGenotypes, final int lineNum) {
        // the same line reader is not used for parsing the header and parsing lines, if we see a #, we've seen a header line
        if (line.startsWith(VCFHeader.HEADER_INDICATOR)) return null;

        // our header cannot be null, we need the genotype sample names and counts
        if (header == null) throw new TribbleException("VCF Header cannot be null when decoding a record");

        String[] myParts = parts.get();
        if (myParts == null) {
            parts.set(new String[Math.min(header.getColumnCount(), NUM_STANDARD_FIELDS + 1)]);
            myParts = parts.get();
        }

        final int nParts = ParsingUtils.split(line, myParts, VCFConstants.FIELD_SEPARATOR_CHAR, true);

        // if we have don't have a header, or we have a header with no genotyping data check that we
        // have eight columns.  Otherwise check that we have nine (normal columns + genotyping data)
        if (( (header == null || !header.hasGenotypingData()) && nParts != NUM_STANDARD_FIELDS) ||
                (header != null && header.hasGenotypingData() && nParts != (NUM_STANDARD_FIELDS + 1)) )
            throw new TribbleException("Line " + lineNum + ": there aren't enough columns for line " + line + " (we expected " + (header == null ? NUM_STANDARD_FIELDS : NUM_STANDARD_FIELDS + 1) +
                    " tokens, and saw " + nParts + " )");

        return parseVCFLine(myParts, includeGenotypes, lineNum);
    }

    /**
     * parse out the VCF line
     *
     * @param parts the parts split up
     * @return a variant context object
     */
    private VariantContext parseVCFLine(final String[] parts, final boolean includeGenotypes, final int lineNum) {
        VariantContextBuilder builder = new VariantContextBuilder();
        builder.source(getName());

        // parse out the required fields
        final String chr = getCachedString(parts[0]);
        builder.chr(chr);
        int pos = -1;
        try {
            pos = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            generateException(parts[1] + " is not a valid start position in the VCF format");
        }
        builder.start(pos);

        if ( parts[2].isEmpty() )
            generateException("The VCF specification requires a valid ID field");
        else if ( parts[2].equals(VCFConstants.EMPTY_ID_FIELD) )
            builder.noID();
        else
            builder.id(parts[2]);

        final String ref = parts[3].toUpperCase();
        final String alts = parts[4];
        builder.log10PError(parseQual(parts[5]));

        final List<String> filters = parseFilters(getCachedString(parts[6]));
        if ( filters != null ) {
            builder.filters(new HashSet<>(filters));
        }
        final Map<String, Object> attrs = parseInfo(parts[7]);
        builder.attributes(attrs);

        if ( attrs.containsKey(VCFConstants.END_KEY) ) {
            // update stop with the end key if provided
            try {
                builder.stop(Integer.parseInt(attrs.get(VCFConstants.END_KEY).toString()));
            } catch (Exception e) {
                generateException("the END value in the INFO field is not valid");
            }
        } else {
            builder.stop(pos + ref.length() - 1);
        }

        // get our alleles, filters, and setup an attribute map
        final List<Allele> alleles = parseAlleles(ref, alts, lineNum);
        builder.alleles(alleles);

        // do we have genotyping data
        if (parts.length > NUM_STANDARD_FIELDS && includeGenotypes) {
            final LazyGenotypesContext.LazyParser lazyParser = new LazyVCFGenotypesParser(alleles, chr, pos, lineNum);
            final int nGenotypes = header.getNGenotypeSamples();
            LazyGenotypesContext lazy = new LazyGenotypesContext(lazyParser, parts[8], nGenotypes);

            // did we resort the sample names?  If so, we need to load the genotype data
            if ( !header.samplesWereAlreadySorted() )
                lazy.decode();

            builder.genotypesNoValidation(lazy);
        }

        VariantContext vc = null;
        try {
            vc = builder.make();
        } catch (Exception e) {
            generateException(e.getMessage());
        }

        return vc;
    }

    /**
     * get the name of this codec
     * @return our set name
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * set the name of this codec
     * @param name new name
     */
    @Override
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Return a cached copy of the supplied string.
     *
     * @param str string
     * @return interned string
     */
    protected String getCachedString(String str) {
        String internedString = stringCache.get(str);
        if ( internedString == null ) {
            internedString = new String(str);
            stringCache.put(internedString, internedString);
        }
        return internedString;
    }

    /**
     * parse out the info fields
     * @param infoField the fields
     * @return a mapping of keys to objects
     */
    private Map<String, Object> parseInfo(String infoField) {
        Map<String, Object> attributes = new HashMap<String, Object>();

        if ( infoField.isEmpty() )
            generateException("The VCF specification requires a valid (non-zero length) info field");

        if ( !infoField.equals(VCFConstants.EMPTY_INFO_FIELD) ) {
            if ( infoField.indexOf('\t') != -1 || infoField.indexOf(' ') != -1 )
                generateException("The VCF specification does not allow for whitespace in the INFO field. Offending field value was \"" + infoField + "\"");

            List<String> infoFields = ParsingUtils.split(infoField, VCFConstants.INFO_FIELD_SEPARATOR_CHAR);
            for (int i = 0; i < infoFields.size(); i++) {
                String key;
                Object value;

                int eqI = infoFields.get(i).indexOf("=");
                if ( eqI != -1 ) {
                    key = infoFields.get(i).substring(0, eqI);
                    String valueString = infoFields.get(i).substring(eqI + 1);

                    // split on the INFO field separator
                    List<String> infoValueSplit = ParsingUtils.split(valueString, VCFConstants.INFO_FIELD_ARRAY_SEPARATOR_CHAR);
                    if ( infoValueSplit.size() == 1 ) {
                        value = vcfTextTransformer.decodeText(infoValueSplit.get(0));
                        final VCFInfoHeaderLine headerLine = header.getInfoHeaderLine(key);
                        if ( headerLine != null && headerLine.getType() == VCFHeaderLineType.Flag && value.equals("0") ) {
                            // deal with the case where a flag field has =0, such as DB=0, by skipping the add
                            continue;
                        }
                    } else {
                        value = vcfTextTransformer.decodeText(infoValueSplit);
                    }
                } else {
                    key = infoFields.get(i);
                    final VCFInfoHeaderLine headerLine = header.getInfoHeaderLine(key);
                    if ( headerLine != null && headerLine.getType() != VCFHeaderLineType.Flag ) {
                        if ( GeneralUtils.DEBUG_MODE_ENABLED && ! warnedAboutNoEqualsForNonFlag ) {
                            System.err.println("Found info key " + key + " without a = value, but the header says the field is of type "
                                               + headerLine.getType() + " but this construct is only value for FLAG type fields");
                            warnedAboutNoEqualsForNonFlag = true;
                        }

                        value = VCFConstants.MISSING_VALUE_v4;
                    } else {
                        value = true;
                    }
                }

                // this line ensures that key/value pairs that look like key=; are parsed correctly as MISSING
                if ( "".equals(value) ) value = VCFConstants.MISSING_VALUE_v4;

                attributes.put(key, value);
            }
        }

        return attributes;
    }

    /**
     * create a an allele from an index and an array of alleles
     * @param index the index
     * @param alleles the alleles
     * @return an Allele
     */
    protected static Allele oneAllele(String index, List<Allele> alleles) {
        if ( index.equals(VCFConstants.EMPTY_ALLELE) )
            return Allele.NO_CALL;
        final int i;
        try {
            i = Integer.parseInt(index);
        } catch ( NumberFormatException e ) {
            throw new TribbleException.InternalCodecException("The following invalid GT allele index was encountered in the file: " + index);
        }
        if ( i >= alleles.size() )
            throw new TribbleException.InternalCodecException("The allele with index " + index + " is not defined in the REF/ALT columns in the record");
        return alleles.get(i);
    }


    /**
     * parse genotype alleles from the genotype string
     * @param GT         GT string
     * @param alleles    list of possible alleles
     * @param cache      cache of alleles for GT
     * @return the allele list for the GT string
     */
    protected static List<Allele> parseGenotypeAlleles(String GT, List<Allele> alleles, Map<String, List<Allele>> cache) {
        // cache results [since they are immutable] and return a single object for each genotype
        List<Allele> GTAlleles = cache.get(GT);

        if ( GTAlleles == null ) {
            StringTokenizer st = new StringTokenizer(GT, VCFConstants.PHASING_TOKENS);
            GTAlleles = new ArrayList<Allele>(st.countTokens());
            while ( st.hasMoreTokens() ) {
                String genotype = st.nextToken();
                GTAlleles.add(oneAllele(genotype, alleles));
            }
            cache.put(GT, GTAlleles);
        }

        return GTAlleles;
    }

    /**
     * parse out the qual value
     * @param qualString the quality string
     * @return return a double
     */
    protected static Double parseQual(String qualString) {
        // if we're the VCF 4 missing char, return immediately
        if ( qualString.equals(VCFConstants.MISSING_VALUE_v4))
            return VariantContext.NO_LOG10_PERROR;

        Double val = VCFUtils.parseVcfDouble(qualString);

        // check to see if they encoded the missing qual score in VCF 3 style, with either the -1 or -1.0.  check for val < 0 to save some CPU cycles
        if ((val < 0) && (Math.abs(val - VCFConstants.MISSING_QUALITY_v3_DOUBLE) < VCFConstants.VCF_ENCODING_EPSILON))
            return VariantContext.NO_LOG10_PERROR;

        // scale and return the value
        return val / -10.0;
    }

    /**
     * parse out the alleles
     * @param ref the reference base
     * @param alts a string of alternates to break into alleles
     * @param lineNo  the line number for this record
     * @return a list of alleles, and a pair of the shortest and longest sequence
     */
    protected static List<Allele> parseAlleles(String ref, String alts, int lineNo) {
        List<Allele> alleles = new ArrayList<Allele>(2); // we are almost always biallelic
        // ref
        checkAllele(ref, true, lineNo);
        Allele refAllele = Allele.create(ref, true);
        alleles.add(refAllele);

        if ( alts.indexOf(',') == -1 ) // only 1 alternatives, don't call string split
            parseSingleAltAllele(alleles, alts, lineNo);
        else
            for ( String alt : alts.split(",") )
                parseSingleAltAllele(alleles, alt, lineNo);

        return alleles;
    }

    /**
     * check to make sure the allele is an acceptable allele
     * @param allele the allele to check
     * @param isRef are we the reference allele?
     * @param lineNo  the line number for this record
     */
    private static void checkAllele(String allele, boolean isRef, int lineNo) {
        if ( allele == null || allele.isEmpty() )
            generateException(generateExceptionTextForBadAlleleBases(""), lineNo);

        if ( GeneralUtils.DEBUG_MODE_ENABLED && MAX_ALLELE_SIZE_BEFORE_WARNING != -1 && allele.length() > MAX_ALLELE_SIZE_BEFORE_WARNING ) {
            System.err.println(String.format("Allele detected with length %d exceeding max size %d at approximately line %d, likely resulting in degraded VCF processing performance", allele.length(), MAX_ALLELE_SIZE_BEFORE_WARNING, lineNo));
        }

        if (Allele.wouldBeSymbolicAllele(allele.getBytes())) {
            if ( isRef ) {
                generateException("Symbolic alleles not allowed as reference allele: " + allele, lineNo);
            }
        } else {
            // check for VCF3 insertions or deletions
            if ( (allele.charAt(0) == VCFConstants.DELETION_ALLELE_v3) || (allele.charAt(0) == VCFConstants.INSERTION_ALLELE_v3) )
                generateException("Insertions/Deletions are not supported when reading 3.x VCF's. Please" +
                        " convert your file to VCF4 using VCFTools, available at http://vcftools.sourceforge.net/index.html", lineNo);

            if (!Allele.acceptableAlleleBases(allele, isRef))
                generateException(generateExceptionTextForBadAlleleBases(allele), lineNo);

            if ( isRef && allele.equals(VCFConstants.EMPTY_ALLELE) )
                generateException("The reference allele cannot be missing", lineNo);
        }
    }

    /**
     * Generates the exception text for the case where the allele string contains unacceptable bases.
     *
     * @param allele   non-null allele string
     * @return non-null exception text string
     */
    private static String generateExceptionTextForBadAlleleBases(final String allele) {
        if ( allele.isEmpty() )
            return "empty alleles are not permitted in VCF records";
        if ( allele.contains("[") || allele.contains("]") || allele.contains(":") || allele.contains(".") )
            return "VCF support for complex rearrangements with breakends has not yet been implemented";
        return "unparsable vcf record with allele " + allele;
    }

    /**
     * parse a single allele, given the allele list
     * @param alleles the alleles available
     * @param alt the allele to parse
     * @param lineNo  the line number for this record
     */
    private static void parseSingleAltAllele(List<Allele> alleles, String alt, int lineNo) {
        checkAllele(alt, false, lineNo);

        Allele allele = Allele.create(alt, false);
        if ( ! allele.isNoCall() )
            alleles.add(allele);
    }

    public static boolean canDecodeFile(final String potentialInput, final String MAGIC_HEADER_LINE) {
        try {
            Path path = IOUtil.getPath(potentialInput);
            //isVCFStream closes the stream that's passed in
            return isVCFStream(Files.newInputStream(path), MAGIC_HEADER_LINE) ||
                    isVCFStream(new GZIPInputStream(Files.newInputStream(path)), MAGIC_HEADER_LINE) ||
                    isVCFStream(new BlockCompressedInputStream(Files.newInputStream(path)), MAGIC_HEADER_LINE);
        } catch ( FileNotFoundException e ) {
            return false;
        } catch ( IOException e ) {
            return false;
        }
    }

    private static boolean isVCFStream(final InputStream stream, final String MAGIC_HEADER_LINE) {
        try {
            byte[] buff = new byte[MAGIC_HEADER_LINE.length()];
            int nread = stream.read(buff, 0, MAGIC_HEADER_LINE.length());
            boolean eq = Arrays.equals(buff, MAGIC_HEADER_LINE.getBytes());
            return eq;
        } catch ( IOException e ) {
            return false;
        } catch ( RuntimeException e ) {
            return false;
        } finally {
            try { stream.close(); } catch ( IOException e ) {}
        }
    }


    /**
     * create a genotype map
     *
     * @param str the string
     * @param alleles the list of alleles
     * @return a mapping of sample name to genotype object
     */
    public LazyGenotypesContext.LazyData createGenotypeMap(final String str,
                                                              final List<Allele> alleles,
                                                              final String chr,
                                                              final int pos, 
                                                              final int lineNum) {
        String[] myGenotypeParts = genotypeParts.get();
        if (myGenotypeParts == null) {
            genotypeParts.set(new String[header.getColumnCount() - NUM_STANDARD_FIELDS]);
            myGenotypeParts = genotypeParts.get();
        }

        int nParts = ParsingUtils.split(str, myGenotypeParts, VCFConstants.FIELD_SEPARATOR_CHAR);
        if ( nParts != myGenotypeParts.length )
            generateException("there are " + (nParts-1) + " genotypes while the header requires that " + (myGenotypeParts.length-1) + " genotypes be present for all records at " + chr + ":" + pos, lineNum);

        ArrayList<Genotype> genotypes = new ArrayList<Genotype>(nParts);

        // get the format keys
        List<String> genotypeKeys = ParsingUtils.split(myGenotypeParts[0], VCFConstants.GENOTYPE_FIELD_SEPARATOR_CHAR);

        // cycle through the sample names
        Iterator<String> sampleNameIterator = header.getGenotypeSamples().iterator();

        // clear out our allele mapping
        alleleMap.get().clear();

        // cycle through the genotype strings
        boolean PlIsSet = false;
        for (int genotypeOffset = 1; genotypeOffset < nParts; genotypeOffset++) {
            List<String> genotypeValues = ParsingUtils.split(myGenotypeParts[genotypeOffset], VCFConstants.GENOTYPE_FIELD_SEPARATOR_CHAR);
            genotypeValues = vcfTextTransformer.decodeText(genotypeValues);

            final String sampleName = sampleNameIterator.next();
            final GenotypeBuilder gb = new GenotypeBuilder(sampleName);

            // check to see if the value list is longer than the key list, which is a problem
            if (genotypeKeys.size() < genotypeValues.size())
                generateException("There are too many keys for the sample " + sampleName + ", keys = " + parts.get()[8] + ", values = " + parts.get()[genotypeOffset]);

            int genotypeAlleleLocation = -1;
            if (!genotypeKeys.isEmpty()) {
                gb.maxAttributes(genotypeKeys.size() - 1);

                for (int i = 0; i < genotypeKeys.size(); i++) {
                    final String gtKey = genotypeKeys.get(i);
                    boolean missing = i >= genotypeValues.size();

                    // todo -- all of these on the fly parsing of the missing value should be static constants
                    if (gtKey.equals(VCFConstants.GENOTYPE_KEY)) {
                        genotypeAlleleLocation = i;
                    } else if ( missing ) {
                        // if its truly missing (there no provided value) skip adding it to the attributes
                    } else if (gtKey.equals(VCFConstants.GENOTYPE_FILTER_KEY)) {
                        final List<String> filters = parseFilters(getCachedString(genotypeValues.get(i)));
                        if ( filters != null ) gb.filters(filters);
                    } else if ( genotypeValues.get(i).equals(VCFConstants.MISSING_VALUE_v4) ) {
                        // don't add missing values to the map
                    } else {
                        if (gtKey.equals(VCFConstants.GENOTYPE_QUALITY_KEY)) {
                            if ( genotypeValues.get(i).equals(VCFConstants.MISSING_GENOTYPE_QUALITY_v3) )
                                gb.noGQ();
                            else
                                gb.GQ((int)Math.round(VCFUtils.parseVcfDouble(genotypeValues.get(i))));
                        } else if (gtKey.equals(VCFConstants.GENOTYPE_ALLELE_DEPTHS)) {
                            gb.AD(decodeInts(genotypeValues.get(i)));
                        } else if (gtKey.equals(VCFConstants.GENOTYPE_PL_KEY)) {
                            gb.PL(decodeInts(genotypeValues.get(i)));
                            PlIsSet = true;
                        } else if (gtKey.equals(VCFConstants.GENOTYPE_LIKELIHOODS_KEY)) {
                            // Do not overwrite PL with data from GL
                            if (!PlIsSet) {
                                gb.PL(GenotypeLikelihoods.fromGLField(genotypeValues.get(i)).getAsPLs());
                            }
                        } else if (gtKey.equals(VCFConstants.DEPTH_KEY)) {
                            gb.DP(Integer.parseInt(genotypeValues.get(i)));
                        } else {
                            gb.attribute(gtKey, genotypeValues.get(i));
                        }
                    }
                }
            }

            // check to make sure we found a genotype field if our version is less than 4.1 file
            if ( ! version.isAtLeastAsRecentAs(VCFHeaderVersion.VCF4_1) && genotypeAlleleLocation == -1 )
                generateException("Unable to find the GT field for the record; the GT field is required before VCF4.1");
            if ( genotypeAlleleLocation > 0 )
                generateException("Saw GT field at position " + genotypeAlleleLocation + ", but it must be at the first position for genotypes when present");

            final List<Allele> GTalleles = (genotypeAlleleLocation == -1 ? new ArrayList<Allele>(0) : parseGenotypeAlleles(genotypeValues.get(genotypeAlleleLocation), alleles, alleleMap.get()));
            gb.alleles(GTalleles);
            gb.phased(genotypeAlleleLocation != -1 && genotypeValues.get(genotypeAlleleLocation).indexOf(VCFConstants.PHASED) != -1);

            // add it to the list
            try {
                genotypes.add(gb.make());
            } catch (TribbleException e) {
                throw new TribbleException.InternalCodecException(e.getMessage() + ", at position " + chr+":"+pos);
            }
        }

        return new LazyGenotypesContext.LazyData(genotypes, header.getSampleNamesInOrder(), header.getSampleNameToOffset());
    }

    private static final int[] decodeInts(final String string) {
        List<String> split = ParsingUtils.split(string, ',');
        int [] values = new int[split.size()];
        try {
            for (int i = 0; i < values.length; i++) {
                values[i] = Integer.parseInt(split.get(i));
            }
        } catch (final NumberFormatException e) {
            return null;
        }
        return values;
    }

    /**
     * Forces all VCFCodecs to not perform any on the fly modifications to the VCF header
     * of VCF records.  Useful primarily for raw comparisons such as when comparing
     * raw VCF records
     */
    public final void disableOnTheFlyModifications() {
        doOnTheFlyModifications = false;
    }

    /**
     * Replaces the sample name read from the VCF header with the remappedSampleName. Works only for
     * single-sample VCFs -- attempting to perform sample name remapping for multi-sample VCFs will
     * produce an Exception.
     *
     * @param remappedSampleName replacement sample name for the sample specified in the VCF header
     */
    public void setRemappedSampleName( final String remappedSampleName ) {
        this.remappedSampleName = remappedSampleName;
    }

    protected void generateException(String message) {
        throw new TribbleException(String.format("The provided VCF file is malformed at approximately line number %d: %s", lineNo.get(), message));
    }

    protected static void generateException(String message, int lineNo) {
        throw new TribbleException(String.format("The provided VCF file is malformed at approximately line number %d: %s", lineNo, message));
    }

    @Override
    public TabixFormat getTabixFormat() {
        return TabixFormat.VCF;
    }
}
