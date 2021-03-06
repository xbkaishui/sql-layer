# Required --define's
%{!?_fdb_sql_version: %{error: required _fdb_sql_version}}
%{!?_fdb_sql_release: %{error: required _fdb_sql_release}}
%{!?_fdb_sql_layer_jar: %{error: required _fdb_sql_layer_jar}}
%{!?_fdb_sql_layer_rf_jar: %{error: required _fdb_sql_layer_rf_jar}}
%{!?_fdb_sql_epoch: %{error: required _fdb_sql_epoch}}
%{!?_el_version: %{error: required _el_version}}


Name:       fdb-sql-layer
Version:    %{_fdb_sql_version}
Release:    %{_fdb_sql_release}.%{_el_version}
Group:      Applications/Databases
License:    AGPLv3
URL:        https://foundationdb.com/layers/sql
Packager:   FoundationDB <distribution@foundationdb.com>
Summary:    FoundationDB SQL Layer
Vendor:     FoundationDB

# 0 = Release so no Epoch
%if %{_fdb_sql_epoch} > 0
Epoch:      %{_fdb_sql_epoch}
%endif

Requires:       jre >= 1.7.0, foundationdb-clients >= 3.0.0
%if %{_el_version} == el6
Requires(post): chkconfig >= 0.9, /sbin/service
%else
%if %{_el_version} == el7
Requires(post): /usr/bin/systemctl
%else
%{error: unexpected _el_version %{_el_version}}
%endif
%endif
Requires(pre):  /usr/sbin/useradd, /usr/sbin/groupadd, /usr/bin/getent
BuildArch:      noarch

%description
The FoundationDB SQL Layer is a fault-tolerant and scalable open source RDBMS,
best suited for applications with high concurrency and short transactional
workloads.

# Don't touch jar files
%define __jar_repack %{nil}

%prep
rm -rf "${RPM_BUILD_ROOT}"
mkdir -p "${RPM_BUILD_ROOT}"

%pre
getent group foundationdb >/dev/null || groupadd -r foundationdb >/dev/null
getent passwd foundationdb >/dev/null || useradd -c "FoundationDB" -g foundationdb -s /bin/false -r -d /var/lib/foundationdb foundationdb >/dev/null
exit 0

%post
%if %{_el_version} == el7
  systemctl daemon-reload
%endif
if [ $1 -eq 1 ]; then
    %if %{_el_version} == el6
      /sbin/chkconfig --add fdb-sql-layer
      /sbin/service fdb-sql-layer start &>/dev/null
    %else
      %if %{_el_version} == el7
        /usr/bin/systemctl enable fdb-sql-layer &>/dev/null
        /usr/bin/systemctl start fdb-sql-layer &>/dev/null
      %else
        %{error: unexpected _el_version %{_el_version}}
      %endif
    %endif
else
    %if %{_el_version} == el6
      /sbin/service fdb-sql-layer condrestart &>/dev/null
    %else
      %if %{_el_version} == el7
        /usr/bin/systemctl condrestart fdb-sql-layer &>/dev/null
      %else
        %{error: unexpected _el_version %{_el_version}}
      %endif
    %endif
fi
exit 0

%preun
if [ $1 -eq 0 ]; then
    %if %{_el_version} == el6
      /sbin/service fdb-sql-layer stop &>/dev/null
      /sbin/chkconfig --del fdb-sql-layer
    %else
      %if %{_el_version} == el7
        /usr/bin/systemctl stop fdb-sql-layer &>/dev/null
        /usr/bin/systemctl disable fdb-sql-layer &>/dev/null
      %else
        %{error: unexpected _el_version %{_el_version}}
      %endif
    %endif
fi
exit 0

%install
mkdir -p "${RPM_BUILD_ROOT}/var/lib/foundationdb/sql"
mkdir -p "${RPM_BUILD_ROOT}/var/log/foundationdb/sql"
mkdir -p "${RPM_BUILD_ROOT}/usr/share/doc/fdb-sql-layer"
cp -r etc/ "${RPM_BUILD_ROOT}/"
%if %{_el_version} == el7
  cp -r lib/ "${RPM_BUILD_ROOT}/"
  cp -r run/ "${RPM_BUILD_ROOT}/"
%endif
cp -r usr/ "${RPM_BUILD_ROOT}/"
ln -s /usr/share/foundationdb/sql/%{_fdb_sql_layer_jar} "${RPM_BUILD_ROOT}/usr/share/foundationdb/sql/fdb-sql-layer.jar"
ln -s /usr/share/foundationdb/sql/fdb-sql-layer-routinefw/%{_fdb_sql_layer_rf_jar} "${RPM_BUILD_ROOT}/usr/share/foundationdb/sql/fdb-sql-layer-routinefw/fdb-sql-layer-routinefw.jar"

%clean
rm -rf "${RPM_BUILD_ROOT}"

%files
%defattr(-,root,root)
%config(noreplace) /etc/foundationdb/sql
%doc /usr/share/doc/fdb-sql-layer
%dir /usr/share/foundationdb
/usr/share/foundationdb/sql/
%attr(700,foundationdb,foundationdb) /var/lib/foundationdb/sql
%attr(700,foundationdb,foundationdb) /var/log/foundationdb/sql
%if %{_el_version} == el6
  %attr(755,-,-) /etc/rc.d/init.d/fdb-sql-layer
%else
  %if %{_el_version} == el7
    %attr(644,-,-) /lib/systemd/system/fdb-sql-layer.service
    %attr(755,foundationdb,foundationdb) /run/fdb-sql-layer
  %else
    %{error: unexpected _el_version %{_el_version}}
  %endif
%endif
%attr(755,-,-) /usr/sbin/fdbsqllayer

