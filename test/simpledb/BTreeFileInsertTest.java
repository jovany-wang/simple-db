package simpledb;

import junit.framework.JUnit4TestAdapter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import simpledb.dbfile.BTreeFile;
import simpledb.field.Field;
import simpledb.field.IntField;
import simpledb.operator.Predicate.Op;
import simpledb.page.Page;
import simpledb.page.btree.BTreeInternalPage;
import simpledb.page.btree.BTreeLeafPage;
import simpledb.page.pageid.BTreePageId;
import simpledb.page.pageid.PageId;
import simpledb.systemtest.SimpleDbTestBase;
import simpledb.transaction.TransactionId;
import simpledb.util.BTreeUtility;

import java.io.File;
import java.util.HashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BTreeFileInsertTest extends SimpleDbTestBase {
    private TransactionId tid;

    /**
     * Set up initial resources for each unit test.
     */
    @Before
    public void setUp() throws Exception {
        tid = new TransactionId();
    }

    @After
    public void tearDown() throws Exception {
        Database.getBufferPool().transactionComplete(tid);

        // set the page size back to the default
        BufferPool.resetPageSize();
        Database.reset();
    }

    @Test
    public void testSplitLeafPages() throws Exception {
        File emptyFile = File.createTempFile("empty", ".dat");
        emptyFile.deleteOnExit();
        Database.reset();
        BTreeFile empty = BTreeUtility.createEmptyBTreeFile(emptyFile.getAbsolutePath(), 2, 0, 3);
        int tableId = empty.getId();
        int keyField = 0;

        // create the leaf page
        BTreePageId leftPageId = new BTreePageId(tableId, 2, BTreePageId.LEAF);
        BTreeLeafPage leftPage = BTreeUtility.createRandomLeafPage(leftPageId, 2, keyField,
                0, BTreeUtility.MAX_RAND_VALUE);

        // create the parent page
        BTreePageId parentId = new BTreePageId(tableId, 1, BTreePageId.INTERNAL);
        BTreeInternalPage parent = new BTreeInternalPage(parentId,
                BTreeInternalPage.createEmptyPageData(), keyField);

        // set the pointers
        leftPage.setParentId(parentId);

        Field field = new IntField(BTreeUtility.MAX_RAND_VALUE / 2);
        HashMap<PageId, Page> dirtyPages = new HashMap<PageId, Page>();
        dirtyPages.put(leftPageId, leftPage);
        dirtyPages.put(parentId, parent);
        BTreeLeafPage page = empty.splitLeafPage(tid, dirtyPages, leftPage, field);
        assertTrue(page.getLeftSiblingId() != null || page.getRightSiblingId() != null);
        BTreeLeafPage otherPage;
        if (page.getLeftSiblingId() != null) {
            otherPage = (BTreeLeafPage) dirtyPages.get(page.getLeftSiblingId());
            assertTrue(field.compare(Op.GREATER_THAN_OR_EQ,
                    otherPage.reverseIterator().next().getField(keyField)));
        } else { // page.getRightSiblingId() != null
            otherPage = (BTreeLeafPage) dirtyPages.get(page.getRightSiblingId());
            assertTrue(field.compare(Op.LESS_THAN_OR_EQ,
                    otherPage.reverseIterator().next().getField(keyField)));
        }


        // TODO pass this test
        int totalTuples = page.getNumTuples() + otherPage.getNumTuples();
        assertEquals(BTreeUtility.getNumTuplesPerPage(2), totalTuples);
        assertTrue(BTreeUtility.getNumTuplesPerPage(2) / 2 == page.getNumTuples() ||
                BTreeUtility.getNumTuplesPerPage(2) / 2 + 1 == page.getNumTuples());
        assertTrue(BTreeUtility.getNumTuplesPerPage(2) / 2 == otherPage.getNumTuples() ||
                BTreeUtility.getNumTuplesPerPage(2) / 2 + 1 == otherPage.getNumTuples());
        assertEquals(1, parent.getNumEntries());
    }

    @Test
    public void testSplitInternalPages() throws Exception {
        File emptyFile = File.createTempFile("empty", ".dat");
        emptyFile.deleteOnExit();
        Database.reset();
        int entriesPerPage = BTreeUtility.getNumEntriesPerPage();
        BTreeFile empty = BTreeUtility.createEmptyBTreeFile(emptyFile.getAbsolutePath(), 2, 0, 3 + entriesPerPage);
        int tableId = empty.getId();
        int keyField = 0;

        // create the internal page
        BTreePageId leftPageId = new BTreePageId(tableId, 2, BTreePageId.INTERNAL);
        BTreeInternalPage leftPage = BTreeUtility.createRandomInternalPage(leftPageId, keyField, BTreePageId.LEAF,
                0, BTreeUtility.MAX_RAND_VALUE, 3);

        // create the parent page
        BTreePageId parentId = new BTreePageId(tableId, 1, BTreePageId.INTERNAL);
        BTreeInternalPage parent = new BTreeInternalPage(parentId,
                BTreeInternalPage.createEmptyPageData(), keyField);

        // set the pointers
        leftPage.setParentId(parentId);

        Field field = new IntField(BTreeUtility.MAX_RAND_VALUE / 2);
        HashMap<PageId, Page> dirtyPages = new HashMap<>();
        dirtyPages.put(leftPageId, leftPage);
        dirtyPages.put(parentId, parent);
        BTreeInternalPage page = empty.splitInternalPage(tid, dirtyPages, leftPage, field);
        BTreeInternalPage otherPage;
        assertEquals(1, parent.getNumEntries());
        BTreeEntry parentEntry = parent.iterator().next();
        if (parentEntry.getLeftChild().equals(page.getId())) {
            otherPage = (BTreeInternalPage) dirtyPages.get(parentEntry.getRightChild());
            assertTrue(field.compare(Op.LESS_THAN_OR_EQ,
                    otherPage.iterator().next().getKey()));
        } else { // parentEntry.getRightChild().equals(page.getId())
            otherPage = (BTreeInternalPage) dirtyPages.get(parentEntry.getLeftChild());
            assertTrue(field.compare(Op.GREATER_THAN_OR_EQ,
                    otherPage.reverseIterator().next().getKey()));
        }

        int totalEntries = page.getNumEntries() + otherPage.getNumEntries();
        assertEquals(entriesPerPage - 1, totalEntries);
        assertTrue(entriesPerPage / 2 == page.getNumEntries() ||
                entriesPerPage / 2 - 1 == page.getNumEntries());
        assertTrue(entriesPerPage / 2 == otherPage.getNumEntries() ||
                entriesPerPage / 2 - 1 == otherPage.getNumEntries());
    }

    @Test
    public void testReusePage() throws Exception {
        File emptyFile = File.createTempFile("empty", ".dat");
        emptyFile.deleteOnExit();
        Database.reset();
        BTreeFile empty = BTreeUtility.createEmptyBTreeFile(emptyFile.getAbsolutePath(), 2, 0, 3);
        int tableId = empty.getId();
        int keyField = 0;

        // create the leaf page
        HashMap<PageId, Page> dirtyPages = new HashMap<PageId, Page>();
        empty.setEmptyPage(tid, dirtyPages, 2);
        BTreePageId leftPageId = new BTreePageId(tableId, 3, BTreePageId.LEAF);
        BTreeLeafPage leftPage = BTreeUtility.createRandomLeafPage(leftPageId, 2, keyField, 0, BTreeUtility.MAX_RAND_VALUE);

        // create the parent page
        BTreePageId parentId = new BTreePageId(tableId, 1, BTreePageId.INTERNAL);
        BTreeInternalPage parent = new BTreeInternalPage(parentId, BTreeInternalPage.createEmptyPageData(), keyField);

        // set the pointers
        leftPage.setParentId(parentId);

        Field field = new IntField(BTreeUtility.MAX_RAND_VALUE / 2);
        dirtyPages.put(leftPageId, leftPage);
        dirtyPages.put(parentId, parent);
        BTreeLeafPage page = empty.splitLeafPage(tid, dirtyPages, leftPage, field);
        assertTrue(page.getLeftSiblingId() != null || page.getRightSiblingId() != null);
        BTreeLeafPage otherPage;
        if (page.getLeftSiblingId() != null) {
            otherPage = (BTreeLeafPage) dirtyPages.get(page.getLeftSiblingId());
        } else { // page.getRightSiblingId() != null
            otherPage = (BTreeLeafPage) dirtyPages.get(page.getRightSiblingId());
        }

        assertTrue(page.getId().getPageNumber() == 2 || otherPage.getId().getPageNumber() == 2);
    }

    /**
     * JUnit suite target
     */
    public static junit.framework.Test suite() {
        return new JUnit4TestAdapter(BTreeFileInsertTest.class);
    }
}
