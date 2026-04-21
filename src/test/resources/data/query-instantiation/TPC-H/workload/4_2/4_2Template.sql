select count(*)
from lineitem
where l_commitdate < l_receiptdate + 'Mirage#93';