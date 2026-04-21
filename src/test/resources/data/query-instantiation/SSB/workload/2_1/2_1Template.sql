select sum(lo_extendedprice * lo_discount) as revenue
from lineorder, dates
where lo_orderdate = d_datekey
	and d_yearmonth = 'Mirage#23'
	and lo_discount between 'Mirage#24' and 'Mirage#25'
	and lo_quantity between 'Mirage#26' and 'Mirage#27';